-- Audit records, version 0 (ADR-0022). Holds the authentication events of Sprint 3; the audit module takes it over
-- and extends it in Sprint 9.
--
-- Platform-level on purpose: an authentication event can happen where there is no tenant (sign-in on the platform host),
-- so the table cannot be tenant-scoped. Nothing reads it yet; no endpoint returns audit rows. Sprint 9 decides the read
-- model (rows of the platform versus rows of one tenant, and the row level security that goes with it) before any read
-- path exists. context_tenant_id records the organization host of the request; it is not called tenant_id because the
-- table is platform-level and the schema-conventions test treats a tenant_id column as "tenant data".
--
-- Append-only: the trigger below refuses every update and delete, whoever runs it (the application role, the owner or
-- a hand-written statement). Retention and archiving arrive with Sprint 9 and Sprint 34 and will need a deliberate,
-- audited procedure.

create table audit_record (
    id                uuid        primary key default uuidv7(),
    occurred_at       timestamptz not null default now(),
    event_type        text        not null,
    outcome           text        not null,
    actor_user_id     uuid,
    context_tenant_id uuid,
    reason            text,
    request_id        text,
    trace_id          text,
    attributes        jsonb       not null default '{}'::jsonb,
    version           bigint      not null default 0,
    created_at        timestamptz not null default now(),
    created_by        uuid        not null,
    updated_at        timestamptz not null default now(),
    updated_by        uuid        not null,
    deleted_at        timestamptz,
    deleted_by        uuid,
    constraint audit_record_outcome_known check (outcome in ('SUCCESS', 'FAILURE', 'DENIED')),
    constraint audit_record_type_format check (event_type ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$')
);

create trigger audit_record_row_guard before insert or update on audit_record
    for each row execute function platform_row_guard();

create function platform_audit_append_only() returns trigger
    language plpgsql
as $guard$
begin
    raise exception 'audit records are append-only: % is refused', tg_op using errcode = 'insufficient_privilege';
end;
$guard$;

comment on function platform_audit_append_only() is
    'Refuses every update and delete of an audit record (ADR-0022).';

create trigger audit_record_append_only before update or delete on audit_record
    for each row execute function platform_audit_append_only();

create index audit_record_type_time on audit_record (event_type, occurred_at);
create index audit_record_user_time on audit_record (actor_user_id, occurred_at) where actor_user_id is not null;
create index audit_record_tenant_time on audit_record (context_tenant_id, occurred_at)
    where context_tenant_id is not null;

comment on table audit_record is
    'Append-only audit records (audit v0). Platform-level: events can happen without a tenant (ADR-0022).';
