-- The retained-object rule at the database (Functional track FA1, ADR-0174 section 7, ADR-0181).
--
-- An object definition may say "keep every record of this object" (retainRecords). The record store refuses to delete,
-- restore over or purge such records (RecordGate), and the access-policy editor and the decision API do not offer the
-- delete right. Those are permissions and code. This migration is the layer that holds when they do not: a code path
-- that is added later, a bug, an administrator, or a revoked-and-regranted table right (the manual migration M001 gives
-- the application role "delete" on every table and a re-run grants it again), because a trigger cannot be undone by a
-- grant.
--
--   retained_object   the names of the objects whose records are kept. PLATFORM-LEVEL (no tenant column): it is the
--                     platform's own list, the same for every organization, loaded at start from the definition files
--                     (RetainedObjects) and checked against them by a test. INSERT-ONLY: a name is never changed or
--                     removed, the same promise as "never delete or rename a released definition" (ADR-0059).
--
--   object_record     gains two triggers that refuse, for a retained object, (a) a physical delete and (b) an update
--                     that sets deleted_at, because the record store soft-deletes (the recycle bin) and a delete
--                     trigger alone would miss it. The second trigger has a WHEN clause, so an ordinary update of a
--                     record pays nothing. Restoring (deleted_at back to null) is not refused: it keeps a record.
--
-- Expand only: a new table and two new triggers.

create table retained_object (
    id              uuid        primary key default uuidv7(),
    object_api_name text        not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint retained_object_name_shape check (object_api_name ~ '^[A-Z][A-Za-z0-9]{0,39}$')
);

create trigger retained_object_row_guard before insert or update on retained_object
    for each row execute function platform_row_guard();
create unique index retained_object_name_live on retained_object (object_api_name) where deleted_at is null;

create function platform_retained_object_list_guard() returns trigger
    language plpgsql
as $guard$
begin
    raise exception 'retained object guard: the list of retained objects only grows' using errcode = 'check_violation';
end;
$guard$;

comment on function platform_retained_object_list_guard() is
    'The list of retained objects is insert-only: a name is never changed or removed (ADR-0181).';

create trigger retained_object_insert_only before update or delete on retained_object
    for each row execute function platform_retained_object_list_guard();

create function platform_retained_record_guard() returns trigger
    language plpgsql
as $guard$
declare
    kept text;
begin
    if tg_op = 'DELETE' then
        kept := old.object_api_name;
    else
        kept := new.object_api_name;
    end if;
    if exists (select 1 from retained_object r where r.object_api_name = kept and r.deleted_at is null) then
        raise exception 'retained object guard: the records of this object are kept' using errcode = 'check_violation';
    end if;
    if tg_op = 'DELETE' then
        return old;
    end if;
    return new;
end;
$guard$;

comment on function platform_retained_record_guard() is
    'Refuses a physical delete and a soft delete of a record of a retained object; messages name no object and no value (ADR-0181).';

create trigger object_record_retained_delete_guard before delete on object_record
    for each row execute function platform_retained_record_guard();
create trigger object_record_retained_soft_delete_guard before update on object_record
    for each row when (old.deleted_at is null and new.deleted_at is not null)
    execute function platform_retained_record_guard();

comment on table retained_object is
    'The objects whose records are never deleted (insert-only). Platform-level (ADR-0181).';
