-- Audit v1 (Sprint 9, ADR-0054, ADR-0055, ADR-0056).
--
-- What this migration does to audit_record, the append-only table that audit v0 (Sprint 3, V009) created:
--
--   1. New columns: the object and the record a change was about, the value before and after, where the record came
--      from (source), the outbox event it was made from (source_event_id, unique, so an event is recorded once), and
--      two flags that say who may read the row: the organization the row is about, the platform (audience_*).
--   2. A read model that the database enforces. The table stays platform-level (a sign-in on the platform host has no
--      tenant, so it cannot be tenant-scoped), and row level security is enabled for the READ side only: with a tenant
--      set, a reader sees the rows of that organization whose audience includes organizations; with no tenant, a reader
--      sees the rows whose audience includes the platform. Inserting stays open (a record can be about any
--      organization), and the table owner is not subject to the policy (it is the role that migrates and purges).
--   3. Retention without breaking "append-only": the trigger now refuses every update and every delete except one
--      made through platform_audit_purge(), the single security-definer function that deletes records older than a
--      cut-off that is never younger than 30 days. A delete made by the application role itself, or by anybody who
--      logged in as the owner, is still refused: the trigger lets a delete through only when the statement runs under a
--      role that differs from the session's login role and is the table owner, which only a security-definer function
--      can arrange.
--
-- The new columns are filled for the existing rows below. That is an update of an append-only table, so the trigger is
-- switched off for those statements and on again in the same transaction (a one-time, deliberate exception, written
-- here and in ADR-0055). The audience rules mirror AuditAudience in the audit module.

alter table audit_record
    add column object_key           text,
    add column record_id            text,
    add column old_value            text,
    add column new_value            text,
    add column source               text    not null default 'SYSTEM',
    add column source_event_id      uuid,
    add column audience_organization boolean not null default false,
    add column audience_platform    boolean not null default false;

alter table audit_record
    add constraint audit_record_source_known check (source in ('API', 'EVENT', 'SCHEDULER', 'SYSTEM')),
    add constraint audit_record_object_key_format check (object_key is null or object_key ~ '^[a-z][a-z0-9_.-]{0,99}$'),
    add constraint audit_record_record_id_format check (record_id is null or record_id ~ '^[A-Za-z0-9_.:-]{1,64}$'),
    add constraint audit_record_change_length
        check (char_length(old_value) <= 500 and char_length(new_value) <= 500);

-- Backfill: the source from the request id (a record made while serving a request came from the API), the audiences
-- from the type and the presence of an organization.
alter table audit_record disable trigger audit_record_append_only;
alter table audit_record disable trigger audit_record_row_guard;

update audit_record set
    source = case when request_id is not null then 'API' else 'SYSTEM' end,
    audience_organization = context_tenant_id is not null and not (
        event_type like 'platform.%' and event_type not like 'platform.support_access.%'
        or event_type like 'system.%' or event_type like 'audit.%'),
    audience_platform = case
        when event_type like 'platform.%' or event_type like 'system.%' or event_type like 'audit.%'
            or event_type like 'support_access.%' or event_type like 'tenant.%' or event_type like 'retention.%'
            then true
        when event_type like 'access.%' or event_type like 'membership.%' or event_type like 'organization.%'
            then false
        else context_tenant_id is null
    end;

alter table audit_record enable trigger audit_record_row_guard;
alter table audit_record enable trigger audit_record_append_only;

create unique index audit_record_source_event on audit_record (source_event_id)
    where source_event_id is not null and deleted_at is null;
create index audit_record_organization_time on audit_record (context_tenant_id, occurred_at desc, id desc)
    where audience_organization and deleted_at is null;
create index audit_record_platform_time on audit_record (occurred_at desc, id desc)
    where audience_platform and deleted_at is null;
create index audit_record_object_time on audit_record (context_tenant_id, object_key, occurred_at desc)
    where object_key is not null;

-- The read model, enforced by the database.
alter table audit_record enable row level security;

create policy audit_record_insert on audit_record for insert
    with check (true);
-- Updates and deletes are reachable by the policies on purpose: a statement that tries to change a record must be refused
-- loudly by the append-only trigger below, not silently match no row.
create policy audit_record_update on audit_record for update
    using (true) with check (true);
create policy audit_record_delete on audit_record for delete
    using (true);
create policy audit_record_select on audit_record for select
    using (case
        when (select platform_current_tenant()) is not null
            then context_tenant_id = (select platform_current_tenant()) and audience_organization
        else audience_platform
    end);

-- Append-only, with one narrow door for retention.
create or replace function platform_audit_append_only() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'DELETE'
            and current_user <> session_user
            and current_user = (select pg_get_userbyid(c.relowner) from pg_class c where c.oid = tg_relid) then
        return old;
    end if;
    raise exception 'audit records are append-only: % is refused', tg_op using errcode = 'insufficient_privilege';
end;
$guard$;

comment on function platform_audit_append_only() is
    'Refuses every update and delete of an audit record; a delete passes only when made through platform_audit_purge (ADR-0055).';

create function platform_audit_purge(older_than timestamptz, batch_size integer) returns bigint
    language plpgsql
    security definer
    set search_path = public, pg_temp
as $purge$
declare
    removed bigint;
begin
    if older_than > now() - interval '30 days' then
        raise exception 'audit purge: records younger than 30 days are never purged' using errcode = 'insufficient_privilege';
    end if;
    if batch_size is null or batch_size < 1 or batch_size > 10000 then
        raise exception 'audit purge: the batch size is between 1 and 10000' using errcode = 'invalid_parameter_value';
    end if;
    with doomed as (
        select id from audit_record where occurred_at < older_than
        order by occurred_at, id limit batch_size for update skip locked)
    delete from audit_record a using doomed d where a.id = d.id;
    get diagnostics removed = row_count;
    if removed > 0 then
        insert into audit_record (event_type, outcome, attributes, source, audience_platform, created_by, updated_by)
        values ('audit.records.purged', 'SUCCESS',
                jsonb_build_object('removed', removed::text, 'older_than', older_than::text), 'SCHEDULER', true,
                '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000');
    end if;
    return removed;
end;
$purge$;

comment on function platform_audit_purge(timestamptz, integer) is
    'The only way to delete audit records: removes up to batch_size records older than the cut-off (never younger than 30 days) and records that it did (ADR-0055).';

comment on table audit_record is
    'Append-only audit records (audit v1). Platform-level, with a read policy by audience: an organization reads its own rows, the platform reads its own (ADR-0054, ADR-0055).';
