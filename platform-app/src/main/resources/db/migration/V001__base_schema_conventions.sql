-- Base schema conventions (ADR-0010).
--
-- Every table of the platform carries the same bookkeeping columns:
--
--     id          uuid         primary key default uuidv7()    time-ordered identifier
--     version     bigint       not null default 0              optimistic concurrency counter
--     created_at  timestamptz  not null default now()
--     created_by  uuid         not null                        actor; the platform itself is the all-zero uuid
--     updated_at  timestamptz  not null default now()
--     updated_by  uuid         not null
--     deleted_at  timestamptz                                  soft delete: null means the row is live
--     deleted_by  uuid                                         set exactly when deleted_at is set
--
-- plus a trigger that calls platform_row_guard() before every insert and update. The trigger makes the
-- conventions impossible to forget, because the database refuses what breaks them:
--
--     create trigger <table>_row_guard before insert or update on <table>
--         for each row execute function platform_row_guard();
--
-- This migration creates only the guard function. Tables arrive with the sprints that own them; the
-- schema-conventions integration test fails when a table does not follow the pattern.
--
-- Migrations are forward-only and run as the owner role (ADR-0009).

create function platform_row_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        -- The database clock and the first version are not the caller's to choose.
        new.created_at := now();
        new.updated_at := new.created_at;
        new.version := 0;
        new.deleted_at := null;
        new.deleted_by := null;
        return new;
    end if;

    -- tg_op = 'UPDATE'. Messages name the table only, never a value.
    if new.id is distinct from old.id then
        raise exception 'row guard: id of % is immutable', tg_table_name using errcode = 'check_violation';
    end if;
    if new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
        raise exception 'row guard: created_at and created_by of % are immutable', tg_table_name
            using errcode = 'check_violation';
    end if;
    if new.version is distinct from old.version + 1 then
        raise exception 'row guard: an update of % must increase version by exactly one', tg_table_name
            using errcode = 'check_violation';
    end if;
    if old.deleted_at is not null and new.deleted_at is not null then
        raise exception 'row guard: a deleted row of % is read-only until it is restored', tg_table_name
            using errcode = 'check_violation';
    end if;
    if new.deleted_at is not null then
        if new.deleted_by is null then
            raise exception 'row guard: deleting a row of % requires deleted_by', tg_table_name
                using errcode = 'check_violation';
        end if;
        new.deleted_at := now();
    else
        new.deleted_by := null;
    end if;
    new.updated_at := now();
    return new;
end;
$guard$;

comment on function platform_row_guard() is
    'Enforces the base schema conventions (ADR-0010): database-owned timestamps, immutable id and creation data, version +1 on every update, soft delete bookkeeping.';
