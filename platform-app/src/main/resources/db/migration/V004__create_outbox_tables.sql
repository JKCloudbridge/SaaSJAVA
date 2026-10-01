-- Transactional outbox and idempotent consumers (decision D3, ADR-0016) with tenant isolation (ADR-0015).
--
-- outbox_event:    one row per event, written in the same transaction as the change it describes. A relay claims due
--                  rows, hands them to the registered handlers and marks them delivered, retried or dead.
-- processed_event: one row per (consumer, event), written in the same transaction as the handler's own changes. It is
--                  the idempotency key store: a redelivered event finds its row and is skipped.
--
-- Both tables are tenant-scoped. The relay is platform infrastructure that must see due events of every tenant, so
-- these two tables (and only these) admit the named system scope 'outbox_relay' next to the tenant: it may read
-- rows of any tenant, update the events and delete expired rows (retention). It cannot insert a row for a tenant it is
-- not running as, and a row can never change tenant (platform_tenant_guard). A test lists the tables that admit a
-- system scope.

create table outbox_event (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    user_id         uuid,
    membership_id   uuid,
    event_type      text        not null,
    payload         jsonb       not null,
    status          text        not null default 'PENDING',
    attempts        integer     not null default 0,
    next_attempt_at timestamptz not null default now(),
    locked_until    timestamptz,
    last_error_type text,
    delivered_at    timestamptz,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint outbox_event_type_length check (char_length(event_type) between 3 and 100),
    constraint outbox_event_status_known check (status in ('PENDING', 'DELIVERED', 'DEAD')),
    constraint outbox_event_attempts_positive check (attempts >= 0),
    -- The last failure is recorded as a type name only: library messages can quote the data that failed.
    constraint outbox_event_error_type_length check (last_error_type is null or char_length(last_error_type) <= 200)
);

create trigger outbox_event_row_guard before insert or update on outbox_event
    for each row execute function platform_row_guard();
create trigger outbox_event_tenant_guard before update on outbox_event
    for each row execute function platform_tenant_guard();

-- Tenant first: the isolation filter and any per-tenant view (dead letters of one tenant) use it.
create index outbox_event_tenant on outbox_event (tenant_id, created_at);
-- The relay's question across tenants: what is due, oldest first. Small, because delivered rows leave the index.
create index outbox_event_due on outbox_event (next_attempt_at, id) where status = 'PENDING';
-- Retention: delivered rows are removed after a while.
create index outbox_event_delivered on outbox_event (delivered_at) where status = 'DELIVERED';

alter table outbox_event enable row level security;
alter table outbox_event force row level security;

create policy outbox_event_insert on outbox_event for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy outbox_event_select on outbox_event for select
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('outbox_relay'));
create policy outbox_event_update on outbox_event for update
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('outbox_relay'))
    with check (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('outbox_relay'));
create policy outbox_event_delete on outbox_event for delete
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('outbox_relay'));

comment on table outbox_event is
    'Transactional outbox (ADR-0016). Tenant-scoped; the outbox_relay system scope may read and update across tenants.';

create table processed_event (
    id         uuid        primary key default uuidv7(),
    tenant_id  uuid        not null default platform_current_tenant() references tenant (id),
    consumer   text        not null,
    event_id   uuid        not null,
    version    bigint      not null default 0,
    created_at timestamptz not null default now(),
    created_by uuid        not null,
    updated_at timestamptz not null default now(),
    updated_by uuid        not null,
    deleted_at timestamptz,
    deleted_by uuid,
    constraint processed_event_consumer_length check (char_length(consumer) between 1 and 100)
);

create trigger processed_event_row_guard before insert or update on processed_event
    for each row execute function platform_row_guard();
create trigger processed_event_tenant_guard before update on processed_event
    for each row execute function platform_tenant_guard();

-- The idempotency key: one consumer handles one event once.
create unique index processed_event_consumer_event on processed_event (consumer, event_id) where deleted_at is null;
create index processed_event_tenant on processed_event (tenant_id, created_at);
-- Retention: markers older than the longest possible redelivery are removed.
create index processed_event_created on processed_event (created_at);

alter table processed_event enable row level security;
alter table processed_event force row level security;

-- Retention is platform work across tenants: the relay must see the expired markers to delete them, so select and delete
-- admit the system scope; inserting and updating stay with the tenant alone.
create policy processed_event_insert on processed_event for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy processed_event_select on processed_event for select
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('outbox_relay'));
create policy processed_event_update on processed_event for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy processed_event_delete on processed_event for delete
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('outbox_relay'));

comment on table processed_event is
    'Idempotency keys of consumers (ADR-0016). Tenant-scoped; the outbox_relay system scope may delete expired rows.';
