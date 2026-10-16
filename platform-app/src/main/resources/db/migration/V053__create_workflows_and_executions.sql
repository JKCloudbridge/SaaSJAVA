-- Workflows, their versions, their executions and their schedules (Sprint 22, ADR-0133 to ADR-0137).
--
-- Definitions are PUBLISHED metadata like validation_rule: a row here is a workflow the organization's records are
-- reacted to by. Drafts stay in the change-set tables of V033 (ADR-0066).
--
--   workflow            The identity of a workflow (object, API name), its label, whether it starts new executions
--                       and the number of the definition that new executions use. One per object and API name.
--   workflow_version    One immutable definition (trigger, optional condition, actions). A new row only when the
--                       definition changed; old rows stay while an execution names them (ADR-0136).
--   workflow_execution  One run for one cause: a record event or one firing of a schedule. Tenant-scoped with forced
--                       row level security; the dispatcher claims queued rows of every organization through the
--                       named system scope 'workflow_dispatch' (ADR-0135). Never holds a record value, a condition
--                       or anything a person typed (ADR-0137).
--   workflow_step       One action of an execution: its status, its times and the type name of a failure.
--   workflow_schedule   When the next firing of a scheduled workflow is due, and how far a firing has got.
--
-- What must hold even if the application had a bug:
--   * the identity of a workflow (API name, object) never changes, and a version is never changed;
--   * one cause makes one execution: (organization, idempotency key) is unique;
--   * an execution that has ended says when, and one that has not does not;
--   * the texts, the names and the reasons are bounded and shaped.
--
-- The change set table of V033 learns the kinds of change of workflows. It also learns the three kinds of record page
-- assignments that Sprint 20 added in the application but not here (a record page assignment could not be put into a
-- change set). Widening a check is backward compatible; nothing is removed.

-- ---------------------------------------------------------------------------------------------------------------
-- change sets learn the missing and the new kinds of change
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
    'CREATE_WORKFLOW', 'UPDATE_WORKFLOW', 'DELETE_WORKFLOW'));

-- ---------------------------------------------------------------------------------------------------------------
-- workflow
-- ---------------------------------------------------------------------------------------------------------------

create table workflow (
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
    constraint workflow_api_name_shape
        check (api_name ~ '^[A-Z][A-Za-z0-9_]{0,55}__c$' and position('__' in left(api_name, -3)) = 0),
    constraint workflow_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint workflow_label_length check (char_length(label) between 1 and 80),
    constraint workflow_description_length check (char_length(description) <= 500),
    constraint workflow_definition_version_positive check (definition_version > 0)
);

create unique index workflow_tenant_object_api_name
    on workflow (tenant_id, lower(object_api_name), lower(api_name)) where deleted_at is null;

create trigger workflow_row_guard before insert or update on workflow
    for each row execute function platform_row_guard();
create trigger workflow_tenant_guard before update on workflow
    for each row execute function platform_tenant_guard();
create trigger workflow_identity_guard before insert or update on workflow
    for each row execute function platform_definition_identity_guard();
create trigger workflow_metadata_version after insert or update or delete on workflow
    for each row execute function platform_metadata_version_bump();

alter table workflow enable row level security;
alter table workflow force row level security;
create policy workflow_insert on workflow for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy workflow_select on workflow for select
    using (tenant_id = (select platform_current_tenant()));
create policy workflow_update on workflow for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy workflow_delete on workflow for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table workflow is
    'A workflow of an object: when something happens, if a condition holds, run actions. Published metadata, tenant-scoped (ADR-0015, ADR-0136).';

-- ---------------------------------------------------------------------------------------------------------------
-- workflow_version
-- ---------------------------------------------------------------------------------------------------------------

create function platform_workflow_version_immutable() returns trigger
    language plpgsql
as $guard$
begin
    raise exception 'workflow version guard: a version of a workflow definition is never changed'
        using errcode = 'check_violation';
end;
$guard$;

comment on function platform_workflow_version_immutable() is
    'A version of a workflow definition is never changed; a changed definition is a new version (ADR-0136).';

create table workflow_version (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    workflow_id     uuid        not null references workflow (id),
    version_number  integer     not null,
    trigger_kind    text        not null,
    trigger         jsonb       not null,
    condition       text,
    actions         jsonb       not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint workflow_version_number_positive check (version_number > 0),
    constraint workflow_version_trigger_known check (trigger_kind in (
        'RECORD_CREATED', 'RECORD_UPDATED', 'RECORD_DELETED', 'FIELD_CHANGED',
        'EVERY_DAY', 'EVERY_WEEK', 'DATE_TIME', 'DAYS_AFTER_DATE')),
    constraint workflow_version_trigger_is_object check (jsonb_typeof(trigger) = 'object'),
    constraint workflow_version_condition_length
        check (condition is null or char_length(condition) between 1 and 4000),
    constraint workflow_version_actions_is_array check (jsonb_typeof(actions) = 'array'),
    constraint workflow_version_size check (octet_length(trigger::text) + octet_length(actions::text) <= 200000)
);

create unique index workflow_version_tenant_number
    on workflow_version (tenant_id, workflow_id, version_number) where deleted_at is null;

create trigger workflow_version_row_guard before insert or update on workflow_version
    for each row execute function platform_row_guard();
create trigger workflow_version_tenant_guard before update on workflow_version
    for each row execute function platform_tenant_guard();
create trigger workflow_version_immutable before update on workflow_version
    for each row execute function platform_workflow_version_immutable();

alter table workflow_version enable row level security;
alter table workflow_version force row level security;
create policy workflow_version_insert on workflow_version for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy workflow_version_select on workflow_version for select
    using (tenant_id = (select platform_current_tenant()));
create policy workflow_version_update on workflow_version for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy workflow_version_delete on workflow_version for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table workflow_version is
    'One immutable definition of a workflow. A new row only when the trigger, condition or actions changed (ADR-0136).';

-- Removes old definitions that no execution names: not the current one of a workflow, and not one an execution still
-- points at. The foreign key is the truth about "named": a definition that an execution names (also one created while
-- this runs) is left alone. Runs as the caller, so row level security shows only the caller's organization.
create function platform_workflow_version_cleanup(older_than interval, batch integer) returns integer
    language plpgsql
as $cleanup$
declare
    candidate uuid;
    removed integer := 0;
begin
    for candidate in
        select v.id
          from workflow_version v join workflow w on w.id = v.workflow_id
         where (w.deleted_at is not null or v.version_number < w.definition_version)
           and v.created_at < now() - older_than
           and not exists (select 1 from workflow_execution e where e.workflow_version_id = v.id)
         order by v.created_at, v.id
         limit batch
    loop
        begin
            delete from workflow_version where id = candidate;
            removed := removed + 1;
        exception when foreign_key_violation then
            null;
        end;
    end loop;
    return removed;
end;
$cleanup$;

comment on function platform_workflow_version_cleanup(interval, integer) is
    'Removes old workflow definitions that no execution names (ADR-0136). Runs as the caller; one batch.';

-- ---------------------------------------------------------------------------------------------------------------
-- workflow_execution
-- ---------------------------------------------------------------------------------------------------------------

create table workflow_execution (
    id                    uuid        primary key default uuidv7(),
    tenant_id             uuid        not null default platform_current_tenant() references tenant (id),
    workflow_id           uuid        not null references workflow (id),
    workflow_version_id   uuid        not null references workflow_version (id),
    -- The names the history shows, kept with the execution so that reading it never touches the definition tables
    -- (which the metadata module owns) and so that it still reads right after the workflow was removed.
    workflow_api_name     text        not null,
    workflow_version_number integer   not null,
    object_api_name       text        not null,
    record_id             uuid        not null,
    record_version        bigint,
    trigger_kind          text        not null,
    event_id              uuid,
    firing_at             timestamptz,
    idempotency_key       text        not null,
    status                text        not null default 'QUEUED',
    reason                text,
    attempts              integer     not null default 0,
    run_at                timestamptz not null default now(),
    locked_until          timestamptz,
    started_at            timestamptz,
    finished_at           timestamptz,
    failure_type          text,
    version               bigint      not null default 0,
    created_at            timestamptz not null default now(),
    created_by            uuid        not null,
    updated_at            timestamptz not null default now(),
    updated_by            uuid        not null,
    deleted_at            timestamptz,
    deleted_by            uuid,
    constraint workflow_execution_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint workflow_execution_workflow_name_shape check (workflow_api_name ~ '^[A-Z][A-Za-z0-9_]{0,55}__c$'),
    constraint workflow_execution_version_number_positive check (workflow_version_number > 0),
    constraint workflow_execution_trigger_known check (trigger_kind in (
        'RECORD_CREATED', 'RECORD_UPDATED', 'RECORD_DELETED', 'FIELD_CHANGED',
        'EVERY_DAY', 'EVERY_WEEK', 'DATE_TIME', 'DAYS_AFTER_DATE')),
    constraint workflow_execution_status_known check (status in (
        'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED')),
    -- A reason is a stable key, never a sentence and never a value.
    constraint workflow_execution_reason_shape check (reason is null or reason ~ '^[a-z][a-z_]{0,59}$'),
    -- The last failure is the type name only: library messages can quote the data that failed.
    constraint workflow_execution_failure_length check (failure_type is null or char_length(failure_type) <= 200),
    constraint workflow_execution_attempts_positive check (attempts >= 0),
    constraint workflow_execution_key_length check (char_length(idempotency_key) between 1 and 200),
    constraint workflow_execution_one_cause check ((event_id is not null) <> (firing_at is not null)),
    constraint workflow_execution_end_known
        check ((status in ('COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED')) = (finished_at is not null))
);

-- One cause makes one execution, whatever delivers the cause twice (ADR-0137).
create unique index workflow_execution_tenant_key on workflow_execution (tenant_id, idempotency_key)
    where deleted_at is null;
-- The dispatcher's question across organizations: what is due, oldest first. Small, because finished rows leave it.
create index workflow_execution_due on workflow_execution (run_at, id)
    where status in ('QUEUED', 'RUNNING') and deleted_at is null;
-- The history of one organization, newest first, and of one record and one workflow.
create index workflow_execution_tenant_created on workflow_execution (tenant_id, created_at desc, id desc);
create index workflow_execution_tenant_record on workflow_execution (tenant_id, record_id, created_at desc);
create index workflow_execution_tenant_workflow on workflow_execution (tenant_id, workflow_id, created_at desc);
-- A version is removed only when nothing names it.
create index workflow_execution_version on workflow_execution (workflow_version_id);
-- Retention across organizations.
create index workflow_execution_finished on workflow_execution (finished_at) where finished_at is not null;

create trigger workflow_execution_row_guard before insert or update on workflow_execution
    for each row execute function platform_row_guard();
create trigger workflow_execution_tenant_guard before update on workflow_execution
    for each row execute function platform_tenant_guard();

alter table workflow_execution enable row level security;
alter table workflow_execution force row level security;
create policy workflow_execution_insert on workflow_execution for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy workflow_execution_select on workflow_execution for select
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('workflow_dispatch'));
create policy workflow_execution_update on workflow_execution for update
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('workflow_dispatch'))
    with check (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('workflow_dispatch'));
create policy workflow_execution_delete on workflow_execution for delete
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('workflow_dispatch'));

comment on table workflow_execution is
    'One run of a workflow for one cause. Tenant-scoped; the workflow_dispatch system scope may claim and clean up across organizations (ADR-0135, ADR-0137). Never holds a record value.';

-- ---------------------------------------------------------------------------------------------------------------
-- workflow_step
-- ---------------------------------------------------------------------------------------------------------------

create table workflow_step (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    execution_id    uuid        not null references workflow_execution (id) on delete cascade,
    position        integer     not null,
    name            text        not null,
    action_type     text        not null,
    status          text        not null default 'RUNNING',
    started_at      timestamptz not null default now(),
    finished_at     timestamptz,
    failure_type    text,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint workflow_step_position_positive check (position > 0),
    constraint workflow_step_name_shape check (name ~ '^[A-Za-z][A-Za-z0-9_]{0,59}$'),
    constraint workflow_step_type_shape check (action_type ~ '^[A-Z][A-Z_]{0,39}$'),
    constraint workflow_step_status_known check (status in ('RUNNING', 'COMPLETED', 'FAILED')),
    constraint workflow_step_failure_length check (failure_type is null or char_length(failure_type) <= 200),
    constraint workflow_step_end_known check ((status = 'RUNNING') = (finished_at is null))
);

create unique index workflow_step_tenant_position on workflow_step (tenant_id, execution_id, position)
    where deleted_at is null;

create trigger workflow_step_row_guard before insert or update on workflow_step
    for each row execute function platform_row_guard();
create trigger workflow_step_tenant_guard before update on workflow_step
    for each row execute function platform_tenant_guard();

alter table workflow_step enable row level security;
alter table workflow_step force row level security;
create policy workflow_step_insert on workflow_step for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy workflow_step_select on workflow_step for select
    using (tenant_id = (select platform_current_tenant()));
create policy workflow_step_update on workflow_step for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy workflow_step_delete on workflow_step for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table workflow_step is
    'One action of an execution: status, times and the type name of a failure. Never a value (ADR-0137).';

-- ---------------------------------------------------------------------------------------------------------------
-- workflow_schedule
-- ---------------------------------------------------------------------------------------------------------------

create table workflow_schedule (
    id                  uuid        primary key default uuidv7(),
    tenant_id           uuid        not null default platform_current_tenant() references tenant (id),
    workflow_id         uuid        not null references workflow (id),
    trigger_kind        text        not null,
    definition_version  integer     not null,
    next_fire_at        timestamptz not null,
    state               text        not null default 'WAITING',
    -- The instant being fired and how far through the records the firing got, so that a dead instance's firing is
    -- taken over where it stopped.
    firing_for          timestamptz,
    firing_after        uuid,
    -- For DAYS_AFTER_DATE: the records whose date is on or before this moment, less the days, were already started.
    covered_through     timestamptz,
    last_fired_at       timestamptz,
    attempts            integer     not null default 0,
    locked_until        timestamptz,
    failure_type        text,
    version             bigint      not null default 0,
    created_at          timestamptz not null default now(),
    created_by          uuid        not null,
    updated_at          timestamptz not null default now(),
    updated_by          uuid        not null,
    deleted_at          timestamptz,
    deleted_by          uuid,
    constraint workflow_schedule_trigger_known check (trigger_kind in (
        'EVERY_DAY', 'EVERY_WEEK', 'DATE_TIME', 'DAYS_AFTER_DATE')),
    constraint workflow_schedule_state_known check (state in ('WAITING', 'FIRING', 'DONE')),
    constraint workflow_schedule_definition_positive check (definition_version > 0),
    constraint workflow_schedule_attempts_positive check (attempts >= 0),
    constraint workflow_schedule_failure_length check (failure_type is null or char_length(failure_type) <= 200),
    constraint workflow_schedule_firing_known check ((state = 'FIRING') = (firing_for is not null))
);

create unique index workflow_schedule_tenant_workflow on workflow_schedule (tenant_id, workflow_id)
    where deleted_at is null;
-- The dispatcher's question across organizations: which schedule is due.
create index workflow_schedule_due on workflow_schedule (next_fire_at, id)
    where state in ('WAITING', 'FIRING') and deleted_at is null;

create trigger workflow_schedule_row_guard before insert or update on workflow_schedule
    for each row execute function platform_row_guard();
create trigger workflow_schedule_tenant_guard before update on workflow_schedule
    for each row execute function platform_tenant_guard();

alter table workflow_schedule enable row level security;
alter table workflow_schedule force row level security;
create policy workflow_schedule_insert on workflow_schedule for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy workflow_schedule_select on workflow_schedule for select
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('workflow_dispatch'));
create policy workflow_schedule_update on workflow_schedule for update
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('workflow_dispatch'))
    with check (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('workflow_dispatch'));
create policy workflow_schedule_delete on workflow_schedule for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table workflow_schedule is
    'When the next firing of a scheduled workflow is due. Tenant-scoped; the workflow_dispatch system scope may claim due rows across organizations (ADR-0135).';
