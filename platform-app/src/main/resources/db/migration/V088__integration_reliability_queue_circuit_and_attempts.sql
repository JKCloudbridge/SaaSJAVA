-- Reliability of the integration flows (Sprint 29, ADR-0227 to ADR-0236). Expand only: columns and tables are added, the
-- lifecycle guard of integration_delivery is replaced by one that knows the queue, and two policies learn the system
-- scope of the outbound worker. Nothing is removed and no existing row changes meaning: a delivery that ended in
-- Sprint 28 keeps its state; a delivery that was pending then is picked up by the worker like any other.
--
-- A delivery is now a unit of queued work. It waits as a row (state PENDING with a due time), is claimed by a worker
-- with a lease (SENDING), may wait for a remote job (WAITING_REMOTE) and ends in DONE, SKIPPED, SUPERSEDED or
-- DEAD_LETTERED. FAILED is kept for a receive that was refused and for the rows of Sprint 28. Nothing here holds a
-- value, a payload, a header or an address: identifiers, stable reason keys, counts and times only. The one
-- exception is remote_ref, the path the other system gave for a job, which exists to be polled and is never shown.

-- ---------------------------------------------------------------------------------------------------- the delivery
alter table integration_delivery
    add column attempts        integer     not null default 0,
    add column run_at          timestamptz not null default now(),
    add column locked_until    timestamptz,
    add column generation      integer     not null default 1,
    add column idempotency_key text,
    add column remote_ref      text,
    add column deadline_at     timestamptz,
    add column queued_reason   text,
    add column last_status_class text,
    add column last_attempt_at timestamptz;

alter table integration_delivery drop constraint integration_delivery_state;
alter table integration_delivery drop constraint integration_delivery_finished;
alter table integration_delivery
    add constraint integration_delivery_state check (state in ('PENDING', 'SENDING', 'WAITING_REMOTE', 'DONE',
        'FAILED', 'SKIPPED', 'DEAD_LETTERED', 'SUPERSEDED')),
    add constraint integration_delivery_finished check (
        (state in ('PENDING', 'SENDING', 'WAITING_REMOTE')) = (finished_at is null)),
    add constraint integration_delivery_attempts check (attempts between 0 and 1000 and generation >= 1),
    add constraint integration_delivery_queued_reason check (queued_reason is null
        or queued_reason ~ '^[a-z_]{1,40}$'),
    add constraint integration_delivery_status_class check (last_status_class is null
        or last_status_class in ('2xx', '3xx', '4xx', '5xx', 'none')),
    add constraint integration_delivery_key_shape check (idempotency_key is null
        or length(idempotency_key) between 1 and 100),
    add constraint integration_delivery_remote_shape check (remote_ref is null or length(remote_ref) between 1 and 500);

-- The claim: due work of an outbound connection, oldest first. Partial, so finished rows (the many) cost nothing.
create index integration_delivery_due
    on integration_delivery (tenant_id, run_at, id)
    where direction = 'OUT' and state in ('PENDING', 'SENDING', 'WAITING_REMOTE') and deleted_at is null;
-- Order within one record: is an earlier delivery of the same record still open?
create index integration_delivery_record_open
    on integration_delivery (tenant_id, connection_id, object_api_name, record_id, created_at, id)
    where direction = 'OUT' and state in ('PENDING', 'SENDING', 'WAITING_REMOTE') and deleted_at is null;
-- What a person filters by: the state of one connection's deliveries, newest first.
create index integration_delivery_tenant_state
    on integration_delivery (tenant_id, connection_id, state, created_at desc) where deleted_at is null;

-- The life of a delivery, held at the database whatever the application does (ADR-0230).
create or replace function platform_integration_delivery_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if new.state not in ('PENDING', 'SKIPPED', 'DEAD_LETTERED') then
            raise exception 'integration guard: a delivery starts queued, skipped or dead-lettered'
                using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if new.connection_id is distinct from old.connection_id
            or new.mapping_version_id is distinct from old.mapping_version_id
            or new.direction is distinct from old.direction or new.kind is distinct from old.kind
            or new.event_id is distinct from old.event_id
            or new.object_api_name is distinct from old.object_api_name
            or new.record_id is distinct from old.record_id then
        raise exception 'integration guard: what a delivery is about never changes' using errcode = 'check_violation';
    end if;
    if old.state in ('DONE', 'FAILED', 'SKIPPED', 'SUPERSEDED') then
        raise exception 'integration guard: a finished delivery is final' using errcode = 'check_violation';
    end if;
    if old.state = 'DEAD_LETTERED' then
        -- The one way back: a replay is a new generation of the same delivery with the attempts counted afresh.
        if new.state <> 'PENDING' or new.generation <> old.generation + 1 or new.attempts <> 0 then
            raise exception 'integration guard: a dead letter is final unless it is replayed'
                using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if new.generation <> old.generation then
        raise exception 'integration guard: only a replay starts a new generation' using errcode = 'check_violation';
    end if;
    if new.attempts < old.attempts then
        raise exception 'integration guard: attempts are never taken back' using errcode = 'check_violation';
    end if;
    if old.state = 'WAITING_REMOTE' and new.state in ('PENDING', 'SENDING') then
        raise exception 'integration guard: a delivery that waits for the other side is not sent again'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

-- A delivery that was claimed or skipped as an event is also a receive: the receiver still uses the old three ends.

-- ----------------------------------------------------------------------------------------------- the attempt log
-- One row per attempt that was made (or lost): when, how long, how it ended, in a stable reason and a class of
-- status. Never a payload, a header, an address or an identifier of the other system (ADR-0234).
create table integration_attempt (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    delivery_id     uuid        not null references integration_delivery (id) on delete cascade,
    connection_id   uuid        not null references integration_connection (id),
    generation      integer     not null,
    attempt_no      integer     not null,
    started_at      timestamptz not null,
    duration_ms     integer     not null default 0,
    outcome         text        not null,
    reason          text,
    status_class    text        not null default 'none',
    http_status     integer,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint integration_attempt_outcome check (outcome in ('SUCCESS', 'ACCEPTED', 'RETRY', 'PERMANENT',
        'INTERRUPTED', 'POLL')),
    constraint integration_attempt_reason_shape check (reason is null or reason ~ '^[a-z_]{1,40}$'),
    constraint integration_attempt_class check (status_class in ('2xx', '3xx', '4xx', '5xx', 'none')),
    constraint integration_attempt_status_range check (http_status is null or http_status between 100 and 599),
    constraint integration_attempt_numbers check (generation >= 1 and attempt_no >= 1 and duration_ms >= 0)
);

create unique index integration_attempt_unique
    on integration_attempt (tenant_id, delivery_id, generation, attempt_no, outcome) where deleted_at is null;
create index integration_attempt_tenant_delivery
    on integration_attempt (tenant_id, delivery_id, generation, attempt_no) where deleted_at is null;
create index integration_attempt_tenant_age
    on integration_attempt (tenant_id, created_at) where deleted_at is null;

create function platform_integration_attempt_guard() returns trigger
    language plpgsql
as $guard$
begin
    raise exception 'integration guard: an attempt record is never changed' using errcode = 'check_violation';
end;
$guard$;

create trigger integration_attempt_row_guard before insert or update on integration_attempt
    for each row execute function platform_row_guard();
create trigger integration_attempt_tenant_guard before update on integration_attempt
    for each row execute function platform_tenant_guard();
create trigger integration_attempt_immutable before update on integration_attempt
    for each row execute function platform_integration_attempt_guard();

-- ------------------------------------------------------------------------------------------- the flow state
-- One row per connection: the circuit breaker, the token bucket and the operator's limits. Runtime state, not a
-- lifecycle state: it never changes the connection's own state and a connection that is off stays unusable whatever
-- this says (ADR-0229). Held in the database so every instance agrees.
create table integration_flow_state (
    id                    uuid        primary key default uuidv7(),
    tenant_id             uuid        not null default platform_current_tenant() references tenant (id),
    connection_id         uuid        not null references integration_connection (id),
    circuit_state         text        not null default 'CLOSED',
    consecutive_failures  integer     not null default 0,
    opened_at             timestamptz,
    open_until            timestamptz,
    probes_in_flight      integer     not null default 0,
    probe_started_at      timestamptz,
    forced                text,
    forced_by             uuid,
    forced_at             timestamptz,
    last_success_at       timestamptz,
    last_failure_at       timestamptz,
    last_failure_reason   text,
    tokens                double precision not null default 0,
    tokens_at             timestamptz not null default now(),
    rate_per_minute       integer,
    max_concurrent        integer,
    version               bigint      not null default 0,
    created_at            timestamptz not null default now(),
    created_by            uuid        not null,
    updated_at            timestamptz not null default now(),
    updated_by            uuid        not null,
    deleted_at            timestamptz,
    deleted_by            uuid,
    constraint integration_flow_state_circuit check (circuit_state in ('CLOSED', 'OPEN', 'HALF_OPEN')),
    constraint integration_flow_state_forced check (forced is null or forced in ('OPEN', 'CLOSED')),
    constraint integration_flow_state_forced_by check ((forced is null) = (forced_by is null)),
    constraint integration_flow_state_counts check (consecutive_failures >= 0 and probes_in_flight >= 0),
    constraint integration_flow_state_open check ((circuit_state = 'CLOSED') = (open_until is null)),
    constraint integration_flow_state_reason_shape check (last_failure_reason is null
        or last_failure_reason ~ '^[a-z_]{1,40}$'),
    constraint integration_flow_state_limits check ((rate_per_minute is null or rate_per_minute between 1 and 600000)
        and (max_concurrent is null or max_concurrent between 1 and 256))
);

create unique index integration_flow_state_tenant_connection
    on integration_flow_state (tenant_id, connection_id) where deleted_at is null;

create function platform_integration_flow_state_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' and new.connection_id is distinct from old.connection_id then
        raise exception 'integration guard: the connection of a flow state never changes'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

create trigger integration_flow_state_row_guard before insert or update on integration_flow_state
    for each row execute function platform_row_guard();
create trigger integration_flow_state_tenant_guard before update on integration_flow_state
    for each row execute function platform_tenant_guard();
create trigger integration_flow_state_shape_guard before insert or update on integration_flow_state
    for each row execute function platform_integration_flow_state_guard();

-- --------------------------------------------------------------------------------------------- row level security
alter table integration_attempt enable row level security;
alter table integration_attempt force row level security;
create policy integration_attempt_insert on integration_attempt for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy integration_attempt_select on integration_attempt for select
    using (tenant_id = (select platform_current_tenant()));
create policy integration_attempt_update on integration_attempt for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy integration_attempt_delete on integration_attempt for delete
    using (tenant_id = (select platform_current_tenant()));

alter table integration_flow_state enable row level security;
alter table integration_flow_state force row level security;
create policy integration_flow_state_insert on integration_flow_state for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy integration_flow_state_select on integration_flow_state for select
    using (tenant_id = (select platform_current_tenant()));
create policy integration_flow_state_update on integration_flow_state for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy integration_flow_state_delete on integration_flow_state for delete
    using (tenant_id = (select platform_current_tenant()));

-- The outbound worker finds and claims due deliveries of every organization in one named scope, like the workflow
-- dispatcher does (ADR-0135, ADR-0231). It may read and claim rows of integration_delivery and nothing else; the work
-- of a claimed row runs in the organization's own tenant context. The update policy admits the claim only.
drop policy integration_delivery_select on integration_delivery;
create policy integration_delivery_select on integration_delivery for select
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('integration_dispatch'));
drop policy integration_delivery_update on integration_delivery;
create policy integration_delivery_update on integration_delivery for update
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('integration_dispatch'))
    with check (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('integration_dispatch'));

comment on table integration_attempt is
    'One attempt to send: when, how long, how it ended, a stable reason and a class of status. Never a payload, header, address or external identifier (ADR-0234).';
comment on table integration_flow_state is
    'The circuit breaker, token bucket and operator limits of one connection. Runtime state, not a lifecycle state (ADR-0229).';
comment on column integration_delivery.remote_ref is
    'The path the other system gave for a long-running job; polled, never shown, never logged (ADR-0233).';
