-- Page layouts, record pages, applications and layout assignments (Sprint 12, ADR-0069 to ADR-0075).
--
-- Four tables, all tenant-scoped (ADR-0015), all PUBLISHED metadata like record_type: a row here is what the
-- organization's members see. Drafts stay in the change-set tables of V033 (ADR-0066).
--
--   page_layout        The structure of a record form: sections, the fields in them, how each field behaves. One
--                      per object and API name. The structure is a JSON document that the application checked
--                      against the schema PageLayoutDefinition before it was stored.
--   record_page        The components of a record page (header, details, related lists, tabs ...), their order,
--                      visibility rules (closed data, never text that is evaluated) and actions. Names its layout.
--   application        A named collection of navigation (an icon, the profiles that see it, the items in order).
--                      It belongs to the organization, not to one object.
--   layout_assignment  Which layout an object uses for a profile (and optionally a record type). With no profile it
--                      is the default layout of the object. The profile is a plain identifier on purpose: deleting
--                      a profile is never blocked by an assignment, the assignment is simply ignored (Sprint 12).
--
-- What must hold even if the application had a bug:
--   * the identity of a definition (API name, object, profile, record type) never changes;
--   * a definition of a custom object names a live custom object of the same organization;
--   * an object has at most one assignment per profile and record type, and one default;
--   * a record type is named only together with a profile.
--
-- Whether the things a definition names exist (fields, record types, the layout of a record page ...) is the
-- dependency graph's question, asked by the application before every publication (ADR-0065), because a group of
-- changes may fix a dependency again.
--
-- The change set tables of V033 learn the new kinds of change here. Widening a check and the length of a name is
-- backward compatible; nothing is removed.

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
    'CREATE_LAYOUT_ASSIGNMENT', 'UPDATE_LAYOUT_ASSIGNMENT', 'DELETE_LAYOUT_ASSIGNMENT'));

-- An application belongs to no object: its changes carry an empty object name.
alter table metadata_change drop constraint metadata_change_object_name_shape;
alter table metadata_change add constraint metadata_change_object_name_shape
    check (object_api_name = '' or object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$');

-- A layout assignment is named by its key (a profile identifier, a tilde and a record type name).
alter table metadata_change drop constraint metadata_change_item_name_length;
alter table metadata_change add constraint metadata_change_item_name_length
    check (item_api_name is null or char_length(item_api_name) <= 120);

-- ---------------------------------------------------------------------------------------------------------------
-- tables
-- ---------------------------------------------------------------------------------------------------------------

create table page_layout (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name text        not null,
    api_name        text        not null,
    label           text        not null,
    description     text        not null default '',
    definition      jsonb       not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint page_layout_api_name_shape
        check (api_name ~ '^[A-Z][A-Za-z0-9_]{0,55}__c$' and position('__' in left(api_name, -3)) = 0),
    constraint page_layout_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint page_layout_label_length check (char_length(label) between 1 and 80),
    constraint page_layout_description_length check (char_length(description) <= 500),
    constraint page_layout_definition_is_object check (jsonb_typeof(definition) = 'object'),
    constraint page_layout_definition_size check (octet_length(definition::text) <= 200000)
);

create unique index page_layout_tenant_object_api_name
    on page_layout (tenant_id, lower(object_api_name), lower(api_name)) where deleted_at is null;

create table record_page (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name text        not null,
    api_name        text        not null,
    label           text        not null,
    description     text        not null default '',
    definition      jsonb       not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint record_page_api_name_shape
        check (api_name ~ '^[A-Z][A-Za-z0-9_]{0,55}__c$' and position('__' in left(api_name, -3)) = 0),
    constraint record_page_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint record_page_label_length check (char_length(label) between 1 and 80),
    constraint record_page_description_length check (char_length(description) <= 500),
    constraint record_page_definition_is_object check (jsonb_typeof(definition) = 'object'),
    constraint record_page_definition_size check (octet_length(definition::text) <= 200000)
);

create unique index record_page_tenant_object_api_name
    on record_page (tenant_id, lower(object_api_name), lower(api_name)) where deleted_at is null;

create table application (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    api_name        text        not null,
    label           text        not null,
    description     text        not null default '',
    definition      jsonb       not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint application_api_name_shape
        check (api_name ~ '^[A-Z][A-Za-z0-9_]{0,55}__c$' and position('__' in left(api_name, -3)) = 0),
    constraint application_label_length check (char_length(label) between 1 and 80),
    constraint application_description_length check (char_length(description) <= 500),
    constraint application_definition_is_object check (jsonb_typeof(definition) = 'object'),
    constraint application_definition_size check (octet_length(definition::text) <= 200000)
);

create unique index application_tenant_api_name
    on application (tenant_id, lower(api_name)) where deleted_at is null;

create table layout_assignment (
    id                  uuid        primary key default uuidv7(),
    tenant_id           uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name     text        not null,
    profile_id          uuid,
    record_type_api_name text,
    layout_api_name     text        not null,
    version             bigint      not null default 0,
    created_at          timestamptz not null default now(),
    created_by          uuid        not null,
    updated_at          timestamptz not null default now(),
    updated_by          uuid        not null,
    deleted_at          timestamptz,
    deleted_by          uuid,
    constraint layout_assignment_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint layout_assignment_layout_name_shape check (layout_api_name ~ '^[A-Z][A-Za-z0-9_]{0,55}__c$'),
    constraint layout_assignment_record_type_shape
        check (record_type_api_name is null or record_type_api_name ~ '^[A-Z][A-Za-z0-9_]{0,55}__c$'),
    constraint layout_assignment_record_type_needs_profile
        check (record_type_api_name is null or profile_id is not null)
);

-- One assignment per object, profile and record type; a missing profile or record type is part of the key.
create unique index layout_assignment_tenant_key
    on layout_assignment (tenant_id, lower(object_api_name),
        coalesce(profile_id, '00000000-0000-0000-0000-000000000000'::uuid),
        lower(coalesce(record_type_api_name, ''))) where deleted_at is null;

-- ---------------------------------------------------------------------------------------------------------------
-- guards
-- ---------------------------------------------------------------------------------------------------------------

create function platform_definition_identity_guard() returns trigger
    language plpgsql
as $guard$
declare
    object_name text := to_jsonb(new) ->> 'object_api_name';
begin
    if tg_op = 'UPDATE' and (to_jsonb(new) ->> 'api_name' is distinct from to_jsonb(old) ->> 'api_name'
            or to_jsonb(new) ->> 'object_api_name' is distinct from to_jsonb(old) ->> 'object_api_name'
            or to_jsonb(new) ->> 'profile_id' is distinct from to_jsonb(old) ->> 'profile_id'
            or to_jsonb(new) ->> 'record_type_api_name' is distinct from to_jsonb(old) ->> 'record_type_api_name') then
        raise exception 'definition guard: the identity of a layout, record page, application or assignment never changes'
            using errcode = 'check_violation';
    end if;
    if new.deleted_at is not null or object_name is null then
        return new;
    end if;
    -- A name ending in __c is a custom object, which must exist here. Anything else is a standard object, which is
    -- known to the application only (ADR-0059): the service checks it.
    if object_name like '%\_\_c' and not exists (
            select 1 from object_definition o
             where o.tenant_id = new.tenant_id and lower(o.api_name) = lower(object_name)
               and o.deleted_at is null) then
        raise exception 'definition guard: the object must be a custom object of this organization'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_definition_identity_guard() is
    'The identity of a layout, record page, application or layout assignment never changes, and one of a custom object names a live custom object of the same organization (ADR-0069).';

-- ---------------------------------------------------------------------------------------------------------------
-- triggers, row level security, comments
-- ---------------------------------------------------------------------------------------------------------------

create trigger page_layout_row_guard before insert or update on page_layout
    for each row execute function platform_row_guard();
create trigger page_layout_tenant_guard before update on page_layout
    for each row execute function platform_tenant_guard();
create trigger page_layout_identity_guard before insert or update on page_layout
    for each row execute function platform_definition_identity_guard();
create trigger page_layout_metadata_version after insert or update or delete on page_layout
    for each row execute function platform_metadata_version_bump();

create trigger record_page_row_guard before insert or update on record_page
    for each row execute function platform_row_guard();
create trigger record_page_tenant_guard before update on record_page
    for each row execute function platform_tenant_guard();
create trigger record_page_identity_guard before insert or update on record_page
    for each row execute function platform_definition_identity_guard();
create trigger record_page_metadata_version after insert or update or delete on record_page
    for each row execute function platform_metadata_version_bump();

create trigger application_row_guard before insert or update on application
    for each row execute function platform_row_guard();
create trigger application_tenant_guard before update on application
    for each row execute function platform_tenant_guard();
create trigger application_identity_guard before insert or update on application
    for each row execute function platform_definition_identity_guard();
create trigger application_metadata_version after insert or update or delete on application
    for each row execute function platform_metadata_version_bump();

create trigger layout_assignment_row_guard before insert or update on layout_assignment
    for each row execute function platform_row_guard();
create trigger layout_assignment_tenant_guard before update on layout_assignment
    for each row execute function platform_tenant_guard();
create trigger layout_assignment_identity_guard before insert or update on layout_assignment
    for each row execute function platform_definition_identity_guard();
create trigger layout_assignment_metadata_version after insert or update or delete on layout_assignment
    for each row execute function platform_metadata_version_bump();

alter table page_layout enable row level security;
alter table page_layout force row level security;
create policy page_layout_insert on page_layout for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy page_layout_select on page_layout for select
    using (tenant_id = (select platform_current_tenant()));
create policy page_layout_update on page_layout for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy page_layout_delete on page_layout for delete
    using (tenant_id = (select platform_current_tenant()));

alter table record_page enable row level security;
alter table record_page force row level security;
create policy record_page_insert on record_page for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy record_page_select on record_page for select
    using (tenant_id = (select platform_current_tenant()));
create policy record_page_update on record_page for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy record_page_delete on record_page for delete
    using (tenant_id = (select platform_current_tenant()));

alter table application enable row level security;
alter table application force row level security;
create policy application_insert on application for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy application_select on application for select
    using (tenant_id = (select platform_current_tenant()));
create policy application_update on application for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy application_delete on application for delete
    using (tenant_id = (select platform_current_tenant()));

alter table layout_assignment enable row level security;
alter table layout_assignment force row level security;
create policy layout_assignment_insert on layout_assignment for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy layout_assignment_select on layout_assignment for select
    using (tenant_id = (select platform_current_tenant()));
create policy layout_assignment_update on layout_assignment for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy layout_assignment_delete on layout_assignment for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table page_layout is
    'The structure of a record form: sections, fields, read or edit behaviour. Published metadata, tenant-scoped (ADR-0015, ADR-0069).';
comment on table record_page is
    'The components of a record page with closed visibility rules and actions. Published metadata, tenant-scoped (ADR-0015, ADR-0069).';
comment on table application is
    'A named collection of navigation seen by members of some profiles. Published metadata, tenant-scoped (ADR-0015, ADR-0069).';
comment on table layout_assignment is
    'Which layout an object uses for a profile and optionally a record type; no profile means the default of the object. Tenant-scoped (ADR-0015, ADR-0071).';
