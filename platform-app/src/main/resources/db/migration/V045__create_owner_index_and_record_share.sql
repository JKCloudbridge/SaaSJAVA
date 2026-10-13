-- Record-level security in the record store (Sprint 17, ADR-0117, ADR-0119): the index on the owner of a record, and
-- the explicit shares of single records.
--
--   object_record_owner   The owner of a record is what most of record-level security asks about: "the records owned by
--                         me", "owned by one of the people below me". Sprint 16 left the index to this sprint (the
--                         owner was filtered only up to the scan limit). Tenant first, then the object, then the owner,
--                         then the identifier, on live rows: the same shape as the indexes of the slots. The owner of a
--                         record changes rarely (a dedicated operation), so the index costs an entry per insert and
--                         nothing per ordinary update (the update stays a heap-only update).
--   record_share          One row per record shared with one user or one public group, with the level of access (1 =
--                         read, 2 = read and write). The data module owns it because a share is a fact about a record
--                         and it is read inside the statement that reads records; the security module never reads
--                         it. It goes with the record: a record removed for good takes its shares with it (the
--                         foreign key), a record in the recycle bin keeps them (they come back with it). A revoked
--                         share is deleted, not marked: the audit trail keeps the history, and the table holds only
--                         what is in force.
--
-- record_share does not carry the security version trigger on purpose (ADR-0115, point 5): nothing is cached from it,
-- the statement reads the live rows, so a share is visible at the next question without dropping the caches of the
-- whole organization.

create index object_record_owner on object_record (tenant_id, object_api_name, owner_id, id)
    where deleted_at is null;

create table record_share (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    object_api_name text        not null,
    record_id       uuid        not null references object_record (id) on delete cascade,
    grantee_type    text        not null,
    grantee_id      uuid        not null,
    access_level    smallint    not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint record_share_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9_]{0,59}$'),
    constraint record_share_grantee_known check (grantee_type in ('USER', 'GROUP')),
    constraint record_share_level_known check (access_level in (1, 2))
);

-- One share of a record per grantee: sharing again changes the level.
create unique index record_share_tenant_record_grantee on record_share (tenant_id, record_id, grantee_id)
    where deleted_at is null;

-- "The records shared with this person or group": the statement of a list reads it.
create index record_share_tenant_grantee on record_share (tenant_id, object_api_name, grantee_id, access_level, record_id)
    where deleted_at is null;

-- The cascade of a removal for good finds the shares of a record by the record alone.
create index record_share_record on record_share (record_id);

create function platform_record_share_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' and (new.object_api_name is distinct from old.object_api_name
            or new.record_id is distinct from old.record_id
            or new.grantee_type is distinct from old.grantee_type
            or new.grantee_id is distinct from old.grantee_id) then
        raise exception 'share guard: a share does not move to another record or grantee'
            using errcode = 'check_violation';
    end if;
    if tg_op = 'INSERT' and not exists (
            select 1 from object_record r where r.id = new.record_id and r.tenant_id = new.tenant_id
               and r.object_api_name = new.object_api_name) then
        raise exception 'share guard: a share belongs to a record of this organization and object'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

comment on function platform_record_share_guard() is
    'A share names a record of its own organization and object and never moves (ADR-0117).';

create trigger record_share_row_guard before insert or update on record_share
    for each row execute function platform_row_guard();
create trigger record_share_tenant_guard before update on record_share
    for each row execute function platform_tenant_guard();
create trigger record_share_use_guard before insert or update on record_share
    for each row execute function platform_record_share_guard();

alter table record_share enable row level security;
alter table record_share force row level security;
create policy record_share_insert on record_share for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy record_share_select on record_share for select
    using (tenant_id = (select platform_current_tenant()));
create policy record_share_update on record_share for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy record_share_delete on record_share for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table record_share is
    'A record shared with one user or one public group, with its level (1 read, 2 read and write). Tenant-scoped, row level security forced (ADR-0117).';
