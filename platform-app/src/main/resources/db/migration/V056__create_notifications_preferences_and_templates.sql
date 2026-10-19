-- Notifications v1 (Sprint 25, ADR-0154 to ADR-0158): in-app notices, the preferences of a person, and the text an
-- administrator writes for the notices and mails the platform sends. Expand only; nothing is removed or renamed.
--
--   notification             One in-app notice for one member. Holds a kind, facts (API names and stable keys, never a
--                            value of a record), the record it is about, a dedupe key and the time it was read. The words
--                            are built when the notice is read (ADR-0155), so a change of text reaches old notices.
--   notification_preference  A person's switch for one category and one channel, per organization. No row means "on".
--                            What the platform sets as a floor cannot be switched off (ADR-0156) and is enforced in code;
--                            a row can never say otherwise for the account category (check below).
--   notification_template    The text an administrator wrote for one kind, one row per kind and organization. Published
--                            metadata like a validation rule: drafts stay in the change-set tables (ADR-0066), a publish
--                            raises the metadata version through the trigger below.
--
-- The change set table of V033 learns the three kinds of change of a notification template, and the workflow versions learn
-- the two before-save triggers (ADR-0160).
--
-- What must hold even if the application had a bug:
--   * a notice exists once per recipient and key (partial unique index), whoever creates it and however often;
--   * facts and keys are shaped (a fact cannot be a sentence), the text of a template is bounded;
--   * every table is tenant-scoped with forced row level security; there is no system scope on any of them.

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
    'CREATE_APPROVAL_PROCESS', 'UPDATE_APPROVAL_PROCESS', 'DELETE_APPROVAL_PROCESS',
    'CREATE_NOTIFICATION_TEMPLATE', 'UPDATE_NOTIFICATION_TEMPLATE', 'DELETE_NOTIFICATION_TEMPLATE'));

-- ---------------------------------------------------------------------------------------------------------------
-- workflows learn the before-save triggers (ADR-0160)
-- ---------------------------------------------------------------------------------------------------------------

-- Added "not valid" and then validated, so the table is scanned without blocking writers (expand only, ADR-0009).
-- Only the versions can hold a before-save trigger: such a workflow is never run as an execution, so the execution
-- table keeps its list.
alter table workflow_version drop constraint workflow_version_trigger_known;
alter table workflow_version add constraint workflow_version_trigger_known check (trigger_kind in (
    'RECORD_CREATED', 'RECORD_UPDATED', 'RECORD_DELETED', 'FIELD_CHANGED',
    'EVERY_DAY', 'EVERY_WEEK', 'DATE_TIME', 'DAYS_AFTER_DATE', 'APPROVAL_OUTCOME',
    'BEFORE_CREATE', 'BEFORE_UPDATE')) not valid;
alter table workflow_version validate constraint workflow_version_trigger_known;

-- ---------------------------------------------------------------------------------------------------------------
-- notification
-- ---------------------------------------------------------------------------------------------------------------

create table notification (
    id               uuid        primary key default uuidv7(),
    tenant_id        uuid        not null default platform_current_tenant() references tenant (id),
    recipient_id     uuid        not null,
    kind             text        not null,
    -- The record the notice is about, so the link can be drawn; a person who cannot read it is told "not found" when
    -- they follow it (the record screen decides, not this table).
    object_api_name  text,
    record_id        uuid,
    -- API names and stable keys of the facts of the kind (ADR-0154). Never a value of a record, never typed text.
    facts            jsonb       not null default '{}'::jsonb,
    dedupe_key       text        not null,
    read_at          timestamptz,
    version          bigint      not null default 0,
    created_at       timestamptz not null default now(),
    created_by       uuid        not null,
    updated_at       timestamptz not null default now(),
    updated_by       uuid        not null,
    deleted_at       timestamptz,
    deleted_by       uuid,
    constraint notification_kind_known check (kind in (
        'APPROVAL_TASK', 'APPROVAL_REMINDER', 'APPROVAL_DECIDED', 'WORKFLOW_NOTICE', 'TRIAL_NOTICE')),
    constraint notification_object_shape check (object_api_name is null or object_api_name ~ '^[A-Za-z][A-Za-z0-9_]{0,59}$'),
    constraint notification_record_with_object check ((object_api_name is null) = (record_id is null)),
    constraint notification_facts_is_object check (jsonb_typeof(facts) = 'object' and octet_length(facts::text) <= 1000),
    constraint notification_dedupe_shape check (dedupe_key ~ '^[A-Za-z0-9:_.\-]{1,160}$')
);

-- THE ONCE (ADR-0154): a notice exists once per recipient and key, however often its cause is retried or replayed.
create unique index notification_tenant_recipient_key on notification (tenant_id, recipient_id, dedupe_key)
    where deleted_at is null;
-- The list of one person, newest first, and the number of unread notices (a partial index: it holds only those).
create index notification_tenant_recipient_created on notification (tenant_id, recipient_id, created_at desc, id desc)
    where deleted_at is null;
create index notification_tenant_recipient_unread on notification (tenant_id, recipient_id)
    where read_at is null and deleted_at is null;
-- Retention walks by age (ADR-0154).
create index notification_tenant_created on notification (tenant_id, created_at) where deleted_at is null;

create trigger notification_row_guard before insert or update on notification
    for each row execute function platform_row_guard();
create trigger notification_tenant_guard before update on notification
    for each row execute function platform_tenant_guard();

alter table notification enable row level security;
alter table notification force row level security;
create policy notification_insert on notification for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy notification_select on notification for select
    using (tenant_id = (select platform_current_tenant()));
create policy notification_update on notification for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy notification_delete on notification for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table notification is
    'An in-app notice for one member of an organization: a kind, facts and the record it is about. The words are built when it is read (ADR-0154, ADR-0155). Never holds a value of a record.';

-- ---------------------------------------------------------------------------------------------------------------
-- notification_preference
-- ---------------------------------------------------------------------------------------------------------------

create table notification_preference (
    id          uuid        primary key default uuidv7(),
    tenant_id   uuid        not null default platform_current_tenant() references tenant (id),
    user_id     uuid        not null,
    category    text        not null,
    channel     text        not null,
    enabled     boolean     not null,
    version     bigint      not null default 0,
    created_at  timestamptz not null default now(),
    created_by  uuid        not null,
    updated_at  timestamptz not null default now(),
    updated_by  uuid        not null,
    deleted_at  timestamptz,
    deleted_by  uuid,
    constraint notification_preference_category_known check (category in ('APPROVALS', 'WORKFLOWS', 'ACCOUNT')),
    constraint notification_preference_channel_known check (channel in ('IN_APP', 'MAIL')),
    -- The floor (ADR-0156): the account category is never switched off; the database says the same as the code.
    constraint notification_preference_floor check (category <> 'ACCOUNT' or enabled)
);

create unique index notification_preference_tenant_user_choice
    on notification_preference (tenant_id, user_id, category, channel) where deleted_at is null;

create trigger notification_preference_row_guard before insert or update on notification_preference
    for each row execute function platform_row_guard();
create trigger notification_preference_tenant_guard before update on notification_preference
    for each row execute function platform_tenant_guard();

alter table notification_preference enable row level security;
alter table notification_preference force row level security;
create policy notification_preference_insert on notification_preference for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy notification_preference_select on notification_preference for select
    using (tenant_id = (select platform_current_tenant()));
create policy notification_preference_update on notification_preference for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy notification_preference_delete on notification_preference for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table notification_preference is
    'A person''s choice of one category and one channel in one organization. No row means on; the account category cannot be off (ADR-0156).';

-- ---------------------------------------------------------------------------------------------------------------
-- notification_template
-- ---------------------------------------------------------------------------------------------------------------

create table notification_template (
    id          uuid        primary key default uuidv7(),
    tenant_id   uuid        not null default platform_current_tenant() references tenant (id),
    kind        text        not null,
    title       text        not null,
    body        text        not null,
    version     bigint      not null default 0,
    created_at  timestamptz not null default now(),
    created_by  uuid        not null,
    updated_at  timestamptz not null default now(),
    updated_by  uuid        not null,
    deleted_at  timestamptz,
    deleted_by  uuid,
    constraint notification_template_kind_known check (kind in (
        'APPROVAL_TASK', 'APPROVAL_REMINDER', 'APPROVAL_DECIDED', 'WORKFLOW_NOTICE')),
    constraint notification_template_title_length check (char_length(title) between 1 and 80),
    constraint notification_template_body_length check (char_length(body) between 1 and 500)
);

create unique index notification_template_tenant_kind on notification_template (tenant_id, kind)
    where deleted_at is null;

create trigger notification_template_row_guard before insert or update on notification_template
    for each row execute function platform_row_guard();
create trigger notification_template_tenant_guard before update on notification_template
    for each row execute function platform_tenant_guard();
create trigger notification_template_metadata_version after insert or update or delete on notification_template
    for each row execute function platform_metadata_version_bump();

alter table notification_template enable row level security;
alter table notification_template force row level security;
create policy notification_template_insert on notification_template for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy notification_template_select on notification_template for select
    using (tenant_id = (select platform_current_tenant()));
create policy notification_template_update on notification_template for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy notification_template_delete on notification_template for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table notification_template is
    'The text an administrator wrote for one kind of notice or mail, one row per kind. Published metadata (ADR-0155). Placeholders come from a closed list of facts; never a value of a record.';
