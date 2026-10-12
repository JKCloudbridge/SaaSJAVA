-- Record types, change sets and releases of an organization's metadata (Sprint 11, ADR-0063 to ADR-0068).
--
-- Four tables, all tenant-scoped (ADR-0015):
--
--   record_type          A variant of an object (New business, Renewal): which fields it offers and which picklist
--                        values it allows. It is PUBLISHED metadata, like object_definition and field_definition: a
--                        row here is what the organization's members see.
--   metadata_change_set  A named group of intended changes that is published all together or not at all.
--   metadata_change     One intended change inside a change set: what to do and the request that describes it. A
--                        change set is a DRAFT: nothing in it is visible to the catalogue, the cache or the security
--                        checks until it is published (ADR-0066), so no draft row exists in the live tables.
--   metadata_release     One row per publication (a change set, a single quick change, or a rollback), numbered 1, 2,
--                        3 ... per organization. It remembers what changed and how to undo it, which is what history
--                        and rollback are built on.
--
-- What must hold even if the application had a bug:
--   * a record type never changes its object or its API name;
--   * a record type of a custom object names a live custom object of the same organization, and a custom object with
--     live record types is not removed (the service removes them first);
--   * an organization has at most one default record type per object;
--   * the changes of a change set can be added or removed only while the set is a draft, and a published or discarded
--     set never becomes a draft again;
--   * a release is never changed or removed, except that a later rollback marks it as rolled back (once).
--
-- The metadata version of V031 also rises with every change of record_type (the catalogue shows record types), but not
-- with a change set: a draft is invisible to everybody who reads the catalogue.

-- ---------------------------------------------------------------------------------------------------------------
-- tables
-- ---------------------------------------------------------------------------------------------------------------

create table record_type (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name text        not null,
    api_name        text        not null,
    label           text        not null,
    description     text        not null default '',
    active          boolean     not null default true,
    is_default      boolean     not null default false,
    layout_ref      text,
    available_fields jsonb,
    picklist_values jsonb       not null default '{}'::jsonb,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint record_type_api_name_shape
        check (api_name ~ '^[A-Z][A-Za-z0-9_]{0,55}__c$' and position('__' in left(api_name, -3)) = 0),
    constraint record_type_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint record_type_label_length check (char_length(label) between 1 and 80),
    constraint record_type_description_length check (char_length(description) <= 500),
    constraint record_type_layout_length check (layout_ref is null or char_length(layout_ref) <= 120),
    constraint record_type_available_fields_is_array
        check (available_fields is null or jsonb_typeof(available_fields) = 'array'),
    constraint record_type_picklist_values_is_object check (jsonb_typeof(picklist_values) = 'object'),
    constraint record_type_default_is_active check (not is_default or active)
);

create unique index record_type_tenant_object_api_name
    on record_type (tenant_id, lower(object_api_name), lower(api_name)) where deleted_at is null;
create unique index record_type_tenant_object_default
    on record_type (tenant_id, lower(object_api_name)) where is_default and deleted_at is null;

create table metadata_change_set (
    id               uuid        primary key default uuidv7(),
    tenant_id        uuid        not null default platform_current_tenant() references tenant (id),
    name             text        not null,
    description      text        not null default '',
    status           text        not null default 'DRAFT',
    release_number   bigint,
    version          bigint      not null default 0,
    created_at       timestamptz not null default now(),
    created_by       uuid        not null,
    updated_at       timestamptz not null default now(),
    updated_by       uuid        not null,
    deleted_at       timestamptz,
    deleted_by       uuid,
    constraint metadata_change_set_name_length check (char_length(name) between 1 and 80),
    constraint metadata_change_set_description_length check (char_length(description) <= 500),
    constraint metadata_change_set_status_known check (status in ('DRAFT', 'PUBLISHED', 'DISCARDED')),
    constraint metadata_change_set_release_only_when_published
        check ((status = 'PUBLISHED') = (release_number is not null))
);

create unique index metadata_change_set_tenant_draft_name
    on metadata_change_set (tenant_id, lower(name)) where status = 'DRAFT' and deleted_at is null;
create index metadata_change_set_tenant_created on metadata_change_set (tenant_id, created_at desc)
    where deleted_at is null;

create table metadata_change (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    change_set_id   uuid        not null references metadata_change_set (id),
    position        integer     not null,
    kind            text        not null,
    object_api_name text        not null,
    item_api_name   text,
    payload         jsonb       not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint metadata_change_kind_known check (kind in (
        'CREATE_OBJECT', 'UPDATE_OBJECT', 'DELETE_OBJECT', 'CREATE_FIELD', 'UPDATE_FIELD', 'DELETE_FIELD',
        'CREATE_RECORD_TYPE', 'UPDATE_RECORD_TYPE', 'DELETE_RECORD_TYPE')),
    constraint metadata_change_position_positive check (position > 0),
    constraint metadata_change_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint metadata_change_item_name_length check (item_api_name is null or char_length(item_api_name) <= 60),
    constraint metadata_change_payload_is_object check (jsonb_typeof(payload) = 'object'),
    constraint metadata_change_payload_size check (octet_length(payload::text) <= 200000)
);

create unique index metadata_change_tenant_set_position
    on metadata_change (tenant_id, change_set_id, position) where deleted_at is null;

create table metadata_release (
    id                  uuid        primary key default uuidv7(),
    tenant_id           uuid        not null default platform_current_tenant() references tenant (id),
    release_number      bigint      not null,
    kind                text        not null,
    change_set_id       uuid        references metadata_change_set (id),
    undoes_release      bigint,
    rolled_back_by      bigint,
    summary             jsonb       not null,
    undo                jsonb       not null,
    metadata_version    bigint      not null,
    version             bigint      not null default 0,
    created_at          timestamptz not null default now(),
    created_by          uuid        not null,
    updated_at          timestamptz not null default now(),
    updated_by          uuid        not null,
    deleted_at          timestamptz,
    deleted_by          uuid,
    constraint metadata_release_number_positive check (release_number > 0),
    constraint metadata_release_kind_known check (kind in ('QUICK', 'CHANGE_SET', 'ROLLBACK')),
    constraint metadata_release_change_set_kind check ((kind = 'CHANGE_SET') = (change_set_id is not null)),
    constraint metadata_release_rollback_kind check ((kind = 'ROLLBACK') = (undoes_release is not null)),
    constraint metadata_release_summary_is_array check (jsonb_typeof(summary) = 'array'),
    constraint metadata_release_undo_is_array check (jsonb_typeof(undo) = 'array')
);

create unique index metadata_release_tenant_number on metadata_release (tenant_id, release_number)
    where deleted_at is null;

-- ---------------------------------------------------------------------------------------------------------------
-- guards
-- ---------------------------------------------------------------------------------------------------------------

create function platform_record_type_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' and (new.api_name is distinct from old.api_name
            or new.object_api_name is distinct from old.object_api_name) then
        raise exception 'record type guard: the API name and the object of a record type never change'
            using errcode = 'check_violation';
    end if;
    if new.deleted_at is not null then
        return new;
    end if;
    -- A name ending in __c is a custom object, which must exist here. Anything else is a standard object, which is
    -- known to the application only (ADR-0059): the service checks it.
    if new.object_api_name like '%\_\_c' and not exists (
            select 1 from object_definition o
             where o.tenant_id = new.tenant_id and lower(o.api_name) = lower(new.object_api_name)
               and o.deleted_at is null) then
        raise exception 'record type guard: the object must be a custom object of this organization'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_record_type_guard() is
    'The identity of a record type never changes and a record type of a custom object belongs to a live custom object of the same organization (ADR-0064).';

-- V031 refused to remove an object with live fields. A custom object with live record types is refused as well; the
-- statement replaces the function body and keeps the trigger that V031 attached to it.
create or replace function platform_object_definition_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' then
        if new.api_name is distinct from old.api_name then
            raise exception 'object guard: the API name of an object never changes' using errcode = 'check_violation';
        end if;
        if new.deleted_at is not null and old.deleted_at is null and exists (
                select 1 from field_definition f
                 where f.tenant_id = old.tenant_id and lower(f.object_api_name) = lower(old.api_name)
                   and f.deleted_at is null) then
            raise exception 'object guard: an object with live fields cannot be removed'
                using errcode = 'check_violation';
        end if;
        if new.deleted_at is not null and old.deleted_at is null and exists (
                select 1 from record_type r
                 where r.tenant_id = old.tenant_id and lower(r.object_api_name) = lower(old.api_name)
                   and r.deleted_at is null) then
            raise exception 'object guard: an object with live record types cannot be removed'
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

create function platform_metadata_change_guard() returns trigger
    language plpgsql
as $guard$
declare
    current_status text;
begin
    select s.status into current_status from metadata_change_set s where s.id = new.change_set_id;
    if current_status is distinct from 'DRAFT' then
        raise exception 'change guard: changes can be added or removed only while the change set is a draft'
            using errcode = 'check_violation';
    end if;
    if tg_op = 'UPDATE' and (new.change_set_id is distinct from old.change_set_id
            or new.kind is distinct from old.kind
            or new.object_api_name is distinct from old.object_api_name
            or new.item_api_name is distinct from old.item_api_name
            or new.payload is distinct from old.payload
            or new.position is distinct from old.position) then
        raise exception 'change guard: a change is never edited, it is removed and added again'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_metadata_change_guard() is
    'Changes of a change set are added or removed only while it is a draft and are never edited (ADR-0066).';

create function platform_metadata_change_set_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' then
        if old.status <> 'DRAFT' and (new.status is distinct from old.status
                or new.name is distinct from old.name
                or new.description is distinct from old.description
                or new.release_number is distinct from old.release_number) then
            raise exception 'change set guard: a published or discarded change set is read-only'
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

comment on function platform_metadata_change_set_guard() is
    'A change set that was published or discarded never changes again (ADR-0066).';

create function platform_metadata_release_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' then
        if new.release_number is distinct from old.release_number
                or new.kind is distinct from old.kind
                or new.change_set_id is distinct from old.change_set_id
                or new.undoes_release is distinct from old.undoes_release
                or new.summary is distinct from old.summary
                or new.undo is distinct from old.undo
                or new.metadata_version is distinct from old.metadata_version then
            raise exception 'release guard: a release is never changed' using errcode = 'check_violation';
        end if;
        if old.rolled_back_by is not null and new.rolled_back_by is distinct from old.rolled_back_by then
            raise exception 'release guard: a release is rolled back once' using errcode = 'check_violation';
        end if;
        if new.deleted_at is not null then
            raise exception 'release guard: a release is never removed' using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$guard$;

comment on function platform_metadata_release_guard() is
    'A release is never changed or removed; a later rollback marks it once (ADR-0067).';

-- ---------------------------------------------------------------------------------------------------------------
-- triggers, row level security, comments
-- ---------------------------------------------------------------------------------------------------------------

create trigger record_type_row_guard before insert or update on record_type
    for each row execute function platform_row_guard();
create trigger record_type_tenant_guard before update on record_type
    for each row execute function platform_tenant_guard();
create trigger record_type_identity_guard before insert or update on record_type
    for each row execute function platform_record_type_guard();
create trigger record_type_metadata_version after insert or update or delete on record_type
    for each row execute function platform_metadata_version_bump();

create trigger metadata_change_set_row_guard before insert or update on metadata_change_set
    for each row execute function platform_row_guard();
create trigger metadata_change_set_tenant_guard before update on metadata_change_set
    for each row execute function platform_tenant_guard();
create trigger metadata_change_set_status_guard before update on metadata_change_set
    for each row execute function platform_metadata_change_set_guard();

create trigger metadata_change_row_guard before insert or update on metadata_change
    for each row execute function platform_row_guard();
create trigger metadata_change_tenant_guard before update on metadata_change
    for each row execute function platform_tenant_guard();
create trigger metadata_change_draft_guard before insert or update on metadata_change
    for each row execute function platform_metadata_change_guard();

create trigger metadata_release_row_guard before insert or update on metadata_release
    for each row execute function platform_row_guard();
create trigger metadata_release_tenant_guard before update on metadata_release
    for each row execute function platform_tenant_guard();
create trigger metadata_release_immutable_guard before update on metadata_release
    for each row execute function platform_metadata_release_guard();

alter table record_type enable row level security;
alter table record_type force row level security;
create policy record_type_insert on record_type for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy record_type_select on record_type for select
    using (tenant_id = (select platform_current_tenant()));
create policy record_type_update on record_type for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy record_type_delete on record_type for delete
    using (tenant_id = (select platform_current_tenant()));

alter table metadata_change_set enable row level security;
alter table metadata_change_set force row level security;
create policy metadata_change_set_insert on metadata_change_set for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy metadata_change_set_select on metadata_change_set for select
    using (tenant_id = (select platform_current_tenant()));
create policy metadata_change_set_update on metadata_change_set for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy metadata_change_set_delete on metadata_change_set for delete
    using (tenant_id = (select platform_current_tenant()));

alter table metadata_change enable row level security;
alter table metadata_change force row level security;
create policy metadata_change_insert on metadata_change for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy metadata_change_select on metadata_change for select
    using (tenant_id = (select platform_current_tenant()));
create policy metadata_change_update on metadata_change for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy metadata_change_delete on metadata_change for delete
    using (tenant_id = (select platform_current_tenant()));

alter table metadata_release enable row level security;
alter table metadata_release force row level security;
create policy metadata_release_insert on metadata_release for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy metadata_release_select on metadata_release for select
    using (tenant_id = (select platform_current_tenant()));
create policy metadata_release_update on metadata_release for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy metadata_release_delete on metadata_release for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table record_type is
    'A variant of an object with its own available fields and picklist values. Published metadata, tenant-scoped (ADR-0015, ADR-0064).';
comment on table metadata_change_set is
    'A draft group of intended metadata changes, published all together or not at all. Invisible to the catalogue until published. Tenant-scoped (ADR-0015, ADR-0066).';
comment on table metadata_change is
    'One intended change inside a change set, with the request that describes it. Tenant-scoped (ADR-0015, ADR-0066).';
comment on table metadata_release is
    'One publication of metadata (a change set, a quick change or a rollback) with what changed and how to undo it. Append-only, tenant-scoped (ADR-0015, ADR-0067).';
