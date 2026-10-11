-- Validation rules (Sprint 13, ADR-0076 to ADR-0081).
--
-- One table, tenant-scoped (ADR-0015), PUBLISHED metadata like record_type and page_layout: a row here is a rule that
-- the organization's records will be judged by (Sprint 15). Drafts stay in the change-set tables of V033 (ADR-0066).
--
--   validation_rule  A condition in the expression language that is TRUE when a record must be REJECTED, the message
--                    the person who is rejected reads, and optionally the field the message is shown on. One per
--                    object and API name. The application parsed the expression and checked it against the fields of
--                    the object before it was stored; it must give true or false.
--
-- What must hold even if the application had a bug:
--   * the identity of a rule (API name, object) never changes;
--   * a rule of a custom object names a live custom object of the same organization;
--   * the text, the message and the names are bounded and shaped: nothing in the table can be an unbounded value.
--
-- Whether the fields a rule uses exist, and whether the expression is valid, is the dependency graph's question, asked
-- by the application before every publication (ADR-0065, ADR-0077): a group of changes may fix a dependency again.
--
-- The change set table of V033 learns the three new kinds of change. Widening a check is backward compatible; nothing
-- is removed.

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
    'CREATE_VALIDATION_RULE', 'UPDATE_VALIDATION_RULE', 'DELETE_VALIDATION_RULE'));

-- ---------------------------------------------------------------------------------------------------------------
-- table
-- ---------------------------------------------------------------------------------------------------------------

create table validation_rule (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name text        not null,
    api_name        text        not null,
    label           text        not null,
    description     text        not null default '',
    expression      text        not null,
    error_message   text        not null,
    error_field     text,
    active          boolean     not null default true,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint validation_rule_api_name_shape
        check (api_name ~ '^[A-Z][A-Za-z0-9_]{0,55}__c$' and position('__' in left(api_name, -3)) = 0),
    constraint validation_rule_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint validation_rule_label_length check (char_length(label) between 1 and 80),
    constraint validation_rule_description_length check (char_length(description) <= 500),
    constraint validation_rule_expression_length check (char_length(expression) between 1 and 4000),
    constraint validation_rule_error_message_length check (char_length(error_message) between 1 and 255),
    constraint validation_rule_error_field_shape
        check (error_field is null or error_field ~ '^[A-Za-z][A-Za-z0-9_]{0,59}$')
);

create unique index validation_rule_tenant_object_api_name
    on validation_rule (tenant_id, lower(object_api_name), lower(api_name)) where deleted_at is null;

-- ---------------------------------------------------------------------------------------------------------------
-- guards, triggers, row level security, comment
-- ---------------------------------------------------------------------------------------------------------------

-- The identity guard of V034 serves here too: it reads the names it guards from the row, so a table without a profile
-- or a record type is simply not affected by those parts.
create trigger validation_rule_row_guard before insert or update on validation_rule
    for each row execute function platform_row_guard();
create trigger validation_rule_tenant_guard before update on validation_rule
    for each row execute function platform_tenant_guard();
create trigger validation_rule_identity_guard before insert or update on validation_rule
    for each row execute function platform_definition_identity_guard();
create trigger validation_rule_metadata_version after insert or update or delete on validation_rule
    for each row execute function platform_metadata_version_bump();

alter table validation_rule enable row level security;
alter table validation_rule force row level security;
create policy validation_rule_insert on validation_rule for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy validation_rule_select on validation_rule for select
    using (tenant_id = (select platform_current_tenant()));
create policy validation_rule_update on validation_rule for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy validation_rule_delete on validation_rule for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table validation_rule is
    'A condition that is true when a record must be rejected, with the message to show. Published metadata, tenant-scoped (ADR-0015, ADR-0078).';
