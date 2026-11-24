-- Webhooks, delivery records and the limits on the public API (Sprint 32, ADR-0274 to ADR-0285). Expand only: tables and
-- one list of notice kinds are added, nothing is removed or renamed. (The numbers V096 and ADR-0274 were the next free
-- ones when this sprint was built.)
--
--   webhook_subscription   One row for each standing instruction of an organization to be told, at an https address of
--                          its own software, when records change: a name, the address, the events asked for, an
--                          optional list of the only objects, the member whose rights it uses (fixed for ever), its
--                          state, the number of its signing secret, and the circuit breaker and token bucket of the
--                          address (runtime state, kept here so that every instance agrees). It never holds a secret:
--                          the signing secrets are sealed in the secrets store under names made from the row.
--   webhook_delivery       One row for each event that a subscription must be told about, and a unit of queued work: it
--                          waits (a due time), is claimed by a worker with a lease, is retried, and ends. Identifiers,
--                          a stable reason, a class of status and times: never a payload body, a header or an answer.
--   webhook_attempt        One row for each attempt that was made or lost; never changed.
--   rate_limit_override    The numbers of the limits on the public API that the platform set for one organization in
--                          place of the defaults (at most one live row each). The reason a platform person gives is
--                          kept in the audit trail only.
--   notification           The list of notice kinds gains WEBHOOK_STOPPED: told to the administrators when the platform
--                          switches a subscription off after a long unbroken failure.
--
-- What must hold even if the application had a bug:
--   * every new table is tenant-scoped with forced row level security; only the delivery table also admits the system
--     scope of the webhook worker, to find and claim due work across organizations and to count the queue;
--   * the organization, the member a subscription acts as and the identifier never change, and a removed
--     subscription stays removed; a subscription changes only while it is switched off, and it is switched on only when
--     its address was verified exactly as it stands;
--   * a subscription moves only along DISABLED, ACTIVE, FAILING as the pure machine says; a delivery moves only along
--     the table of the queue (a finished one is final, a dead letter comes back only as a replay of the next
--     generation), and one event makes one delivery for one subscription;
--   * an attempt row is never changed; a delivery row is deleted only when it has ended and is a day old;
--   * the reasons stored are stable keys, never free text.
-- It feeds no answer of the permission module and no metadata, so it needs no security-version or metadata-version
-- trigger. Nothing in it is ever written to a log, an audit record or an event.

-- ------------------------------------------------------------------------------------------------ subscription
create table webhook_subscription (
    id                    uuid        primary key default uuidv7(),
    tenant_id             uuid        not null default platform_current_tenant() references tenant (id),
    name                  text        not null,
    address               text        not null,
    events                text[]      not null,
    objects               text[],
    membership_id         uuid        not null references membership (id),
    user_id               uuid        not null references platform_user (id),
    state                 text        not null default 'DISABLED',
    disabled_reason       text        default 'new',
    fingerprint           text        not null,
    verified_fingerprint  text,
    verified_at           timestamptz,
    secret_number         integer     not null default 1,
    secret_made_at        timestamptz not null default now(),
    previous_secret_number integer,
    previous_secret_until timestamptz,
    circuit_state         text        not null default 'CLOSED',
    consecutive_failures  integer     not null default 0,
    opened_at             timestamptz,
    open_until            timestamptz,
    probes_in_flight      integer     not null default 0,
    probe_started_at      timestamptz,
    tokens                double precision not null default 0,
    tokens_at             timestamptz not null default now(),
    last_success_at       timestamptz,
    last_failure_at       timestamptz,
    last_failure_reason   text,
    failing_since         timestamptz,
    version               bigint      not null default 0,
    created_at            timestamptz not null default now(),
    created_by            uuid        not null,
    updated_at            timestamptz not null default now(),
    updated_by            uuid        not null,
    deleted_at            timestamptz,
    deleted_by            uuid,
    constraint webhook_subscription_name_shape check (name ~ '^[a-z][a-z0-9-]{1,39}$'),
    constraint webhook_subscription_address_size check (char_length(address) between 1 and 2048),
    constraint webhook_subscription_state check (state in ('DISABLED', 'ACTIVE', 'FAILING')),
    constraint webhook_subscription_events_known
        check (cardinality(events) between 1 and 4
            and events <@ array['record.created', 'record.updated', 'record.deleted', 'record.restored']),
    constraint webhook_subscription_objects_shape
        check (objects is null or (cardinality(objects) between 1 and 50
            and array_to_string(objects, ',') ~ '^[A-Za-z][A-Za-z0-9_]{0,79}(,[A-Za-z][A-Za-z0-9_]{0,79})*$')),
    constraint webhook_subscription_disabled_reason
        check (disabled_reason is null or disabled_reason in ('new', 'switched_off', 'auto_disabled', 'changed')),
    constraint webhook_subscription_on_has_no_reason check ((state = 'DISABLED') = (disabled_reason is not null)),
    constraint webhook_subscription_fingerprint_shape check (fingerprint ~ '^[0-9a-f]{64}$'),
    constraint webhook_subscription_verified_pair check ((verified_fingerprint is null) = (verified_at is null)),
    constraint webhook_subscription_secret check (secret_number >= 1
        and (previous_secret_number is null) = (previous_secret_until is null)
        and (previous_secret_number is null or previous_secret_number < secret_number)),
    constraint webhook_subscription_circuit check (circuit_state in ('CLOSED', 'OPEN', 'HALF_OPEN')),
    constraint webhook_subscription_circuit_open check ((circuit_state = 'CLOSED') = (open_until is null)),
    constraint webhook_subscription_counts check (consecutive_failures >= 0 and probes_in_flight >= 0),
    constraint webhook_subscription_reason_shape check (last_failure_reason is null
        or last_failure_reason ~ '^[a-z_]{1,40}$')
);

create unique index webhook_subscription_tenant_name on webhook_subscription (tenant_id, name)
    where deleted_at is null;
create index webhook_subscription_tenant_state on webhook_subscription (tenant_id, state)
    where deleted_at is null;
create index webhook_subscription_tenant_user on webhook_subscription (tenant_id, user_id)
    where deleted_at is null;

create trigger webhook_subscription_row_guard before insert or update on webhook_subscription
    for each row execute function platform_row_guard();
create trigger webhook_subscription_tenant_guard before update on webhook_subscription
    for each row execute function platform_tenant_guard();

-- The life of a subscription, held at the database whatever the application does (ADR-0276).
create function platform_webhook_subscription_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'DELETE' then
        raise exception 'webhook guard: a subscription is removed by marking it, never deleted'
            using errcode = 'check_violation';
    end if;
    if tg_op = 'INSERT' then
        if new.state <> 'DISABLED' then
            raise exception 'webhook guard: a subscription starts switched off' using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if new.user_id is distinct from old.user_id or new.membership_id is distinct from old.membership_id then
        raise exception 'webhook guard: the member a subscription acts as is immutable'
            using errcode = 'check_violation';
    end if;
    if old.deleted_at is not null then
        raise exception 'webhook guard: a removed subscription is kept as it is' using errcode = 'check_violation';
    end if;
    if new.secret_number < old.secret_number then
        raise exception 'webhook guard: the number of a signing secret never goes back'
            using errcode = 'check_violation';
    end if;
    -- What the subscription is (address, events, objects, name) changes only while it is switched off.
    if (new.address is distinct from old.address or new.events is distinct from old.events
            or new.objects is distinct from old.objects or new.name is distinct from old.name
            or new.fingerprint is distinct from old.fingerprint)
            and not (old.state = 'DISABLED' and new.state = 'DISABLED') then
        raise exception 'webhook guard: a subscription is changed only while it is switched off'
            using errcode = 'check_violation';
    end if;
    if new.state is distinct from old.state then
        if not ((old.state = 'DISABLED' and new.state = 'ACTIVE')
                or (old.state = 'ACTIVE' and new.state in ('FAILING', 'DISABLED'))
                or (old.state = 'FAILING' and new.state in ('ACTIVE', 'DISABLED'))) then
            raise exception 'webhook guard: this move of a subscription is not allowed'
                using errcode = 'check_violation';
        end if;
    end if;
    -- A subscription is on only for an address that answered a verification as it stands now.
    if new.state in ('ACTIVE', 'FAILING') and new.verified_fingerprint is distinct from new.fingerprint then
        raise exception 'webhook guard: a subscription is switched on only after its address was verified'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

create trigger webhook_subscription_life_guard before insert or update or delete on webhook_subscription
    for each row execute function platform_webhook_subscription_guard();

-- --------------------------------------------------------------------------------------------------- delivery
create table webhook_delivery (
    id                uuid        primary key default uuidv7(),
    tenant_id         uuid        not null default platform_current_tenant() references tenant (id),
    subscription_id   uuid        not null references webhook_subscription (id),
    event_id          uuid        not null,
    event_type        text        not null,
    object_api_name   text        not null,
    record_id         uuid        not null,
    record_version    bigint,
    state             text        not null default 'PENDING',
    reason            text,
    queued_reason     text,
    last_status_class text,
    http_status       integer,
    attempts          integer     not null default 0,
    generation        integer     not null default 1,
    run_at            timestamptz not null default now(),
    locked_until      timestamptz,
    last_attempt_at   timestamptz,
    finished_at       timestamptz,
    version           bigint      not null default 0,
    created_at        timestamptz not null default now(),
    created_by        uuid        not null,
    updated_at        timestamptz not null default now(),
    updated_by        uuid        not null,
    deleted_at        timestamptz,
    deleted_by        uuid,
    constraint webhook_delivery_state check (state in ('PENDING', 'SENDING', 'DONE', 'SKIPPED', 'SUPERSEDED',
        'DEAD_LETTERED')),
    constraint webhook_delivery_type check (event_type in ('record.created', 'record.updated', 'record.deleted',
        'record.restored')),
    constraint webhook_delivery_object_shape check (object_api_name ~ '^[A-Za-z][A-Za-z0-9_]{0,79}$'),
    constraint webhook_delivery_finished check ((state in ('PENDING', 'SENDING')) = (finished_at is null)),
    constraint webhook_delivery_numbers check (attempts between 0 and 1000 and generation >= 1),
    constraint webhook_delivery_reason_shape check (reason is null or reason ~ '^[a-z_]{1,40}$'),
    constraint webhook_delivery_queued_reason check (queued_reason is null or queued_reason ~ '^[a-z_]{1,40}$'),
    constraint webhook_delivery_status_class check (last_status_class is null
        or last_status_class in ('2xx', '3xx', '4xx', '5xx', 'none')),
    constraint webhook_delivery_status_range check (http_status is null or http_status between 100 and 599)
);

-- One event makes one delivery for one subscription, whichever instance handles the event, however often the outbox
-- hands it over (ADR-0278).
create unique index webhook_delivery_event on webhook_delivery (tenant_id, subscription_id, event_id)
    where deleted_at is null;
-- The claim: due work, oldest first. Partial, so finished rows (the many) cost nothing.
create index webhook_delivery_due on webhook_delivery (tenant_id, run_at, id)
    where state in ('PENDING', 'SENDING') and deleted_at is null;
-- What a person filters by: the state of one subscription's deliveries, newest first.
create index webhook_delivery_tenant_state on webhook_delivery (tenant_id, subscription_id, state, id desc)
    where deleted_at is null;
-- The clean-up and the supersession of waiting updates of one record.
create index webhook_delivery_record on webhook_delivery (tenant_id, subscription_id, object_api_name, record_id)
    where state = 'PENDING' and deleted_at is null;
create index webhook_delivery_age on webhook_delivery (tenant_id, created_at) where deleted_at is null;

create trigger webhook_delivery_row_guard before insert or update on webhook_delivery
    for each row execute function platform_row_guard();
create trigger webhook_delivery_tenant_guard before update on webhook_delivery
    for each row execute function platform_tenant_guard();

create function platform_webhook_delivery_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'DELETE' then
        if old.state in ('PENDING', 'SENDING') then
            raise exception 'webhook guard: a delivery that is still open is not deleted'
                using errcode = 'check_violation';
        end if;
        if old.created_at > now() - interval '1 day' then
            raise exception 'webhook guard: a delivery is kept for its retention' using errcode = 'check_violation';
        end if;
        return old;
    end if;
    if tg_op = 'INSERT' then
        if new.state not in ('PENDING', 'SKIPPED', 'DEAD_LETTERED') then
            raise exception 'webhook guard: a delivery starts queued, skipped or dead-lettered'
                using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if new.subscription_id is distinct from old.subscription_id or new.event_id is distinct from old.event_id
            or new.event_type is distinct from old.event_type
            or new.object_api_name is distinct from old.object_api_name
            or new.record_id is distinct from old.record_id then
        raise exception 'webhook guard: what a delivery is about never changes' using errcode = 'check_violation';
    end if;
    if old.state in ('DONE', 'SKIPPED', 'SUPERSEDED') then
        raise exception 'webhook guard: a finished delivery is final' using errcode = 'check_violation';
    end if;
    if old.state = 'DEAD_LETTERED' then
        -- The one way back: a replay is a new generation of the same delivery with the attempts counted afresh.
        if new.state <> 'PENDING' or new.generation <> old.generation + 1 or new.attempts <> 0 then
            raise exception 'webhook guard: a dead letter is final unless it is replayed'
                using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if new.generation <> old.generation then
        raise exception 'webhook guard: only a replay starts a new generation' using errcode = 'check_violation';
    end if;
    if new.attempts < old.attempts then
        raise exception 'webhook guard: attempts are never taken back' using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

create trigger webhook_delivery_life_guard before insert or update or delete on webhook_delivery
    for each row execute function platform_webhook_delivery_guard();

-- ----------------------------------------------------------------------------------------------------- attempt
create table webhook_attempt (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    delivery_id     uuid        not null references webhook_delivery (id) on delete cascade,
    subscription_id uuid        not null references webhook_subscription (id),
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
    constraint webhook_attempt_outcome check (outcome in ('SUCCESS', 'RETRY', 'PERMANENT', 'INTERRUPTED')),
    constraint webhook_attempt_reason_shape check (reason is null or reason ~ '^[a-z_]{1,40}$'),
    constraint webhook_attempt_class check (status_class in ('2xx', '3xx', '4xx', '5xx', 'none')),
    constraint webhook_attempt_status_range check (http_status is null or http_status between 100 and 599),
    constraint webhook_attempt_numbers check (generation >= 1 and attempt_no >= 1 and duration_ms >= 0)
);

create unique index webhook_attempt_unique
    on webhook_attempt (tenant_id, delivery_id, generation, attempt_no, outcome) where deleted_at is null;
create index webhook_attempt_tenant_delivery
    on webhook_attempt (tenant_id, delivery_id, generation, attempt_no) where deleted_at is null;

create function platform_webhook_attempt_guard() returns trigger
    language plpgsql
as $guard$
begin
    raise exception 'webhook guard: an attempt record is never changed' using errcode = 'check_violation';
end;
$guard$;

create trigger webhook_attempt_row_guard before insert or update on webhook_attempt
    for each row execute function platform_row_guard();
create trigger webhook_attempt_tenant_guard before update on webhook_attempt
    for each row execute function platform_tenant_guard();
create trigger webhook_attempt_immutable before update on webhook_attempt
    for each row execute function platform_webhook_attempt_guard();

-- ---------------------------------------------------------------------------------------- limit override
create table rate_limit_override (
    id                      uuid        primary key default uuidv7(),
    tenant_id               uuid        not null default platform_current_tenant() references tenant (id),
    organization_per_minute integer,
    organization_per_day    bigint,
    application_per_minute  integer,
    version                 bigint      not null default 0,
    created_at              timestamptz not null default now(),
    created_by              uuid        not null,
    updated_at              timestamptz not null default now(),
    updated_by              uuid        not null,
    deleted_at              timestamptz,
    deleted_by              uuid,
    constraint rate_limit_override_ranges check (
        (organization_per_minute is null or organization_per_minute between 1 and 10000000)
        and (organization_per_day is null or organization_per_day between 1 and 1000000000)
        and (application_per_minute is null or application_per_minute between 1 and 10000000)),
    constraint rate_limit_override_something check (organization_per_minute is not null
        or organization_per_day is not null or application_per_minute is not null)
);

create unique index rate_limit_override_tenant on rate_limit_override (tenant_id) where deleted_at is null;

create trigger rate_limit_override_row_guard before insert or update on rate_limit_override
    for each row execute function platform_row_guard();
create trigger rate_limit_override_tenant_guard before update on rate_limit_override
    for each row execute function platform_tenant_guard();

-- ---------------------------------------------------------------------------------- row level security
alter table webhook_subscription enable row level security;
alter table webhook_subscription force row level security;
create policy webhook_subscription_insert on webhook_subscription for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy webhook_subscription_select on webhook_subscription for select
    using (tenant_id = (select platform_current_tenant()));
create policy webhook_subscription_update on webhook_subscription for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
-- There is no delete policy: a subscription is marked removed, never deleted.

-- The webhook worker finds and claims due deliveries of every organization in one named scope, as the integration
-- worker does (ADR-0231, ADR-0279). It may read and claim rows of webhook_delivery and nothing else; the call of a
-- claimed row runs in the organization's own tenant context.
alter table webhook_delivery enable row level security;
alter table webhook_delivery force row level security;
create policy webhook_delivery_insert on webhook_delivery for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy webhook_delivery_select on webhook_delivery for select
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('webhook_dispatch'));
create policy webhook_delivery_update on webhook_delivery for update
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('webhook_dispatch'))
    with check (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('webhook_dispatch'));
create policy webhook_delivery_delete on webhook_delivery for delete
    using (tenant_id = (select platform_current_tenant()));

alter table webhook_attempt enable row level security;
alter table webhook_attempt force row level security;
create policy webhook_attempt_insert on webhook_attempt for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy webhook_attempt_select on webhook_attempt for select
    using (tenant_id = (select platform_current_tenant()));
create policy webhook_attempt_update on webhook_attempt for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy webhook_attempt_delete on webhook_attempt for delete
    using (tenant_id = (select platform_current_tenant()));

alter table rate_limit_override enable row level security;
alter table rate_limit_override force row level security;
create policy rate_limit_override_insert on rate_limit_override for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy rate_limit_override_select on rate_limit_override for select
    using (tenant_id = (select platform_current_tenant()));
create policy rate_limit_override_update on rate_limit_override for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));

-- --------------------------------------------------------------------------------------------- notices
alter table notification drop constraint notification_kind_known;
alter table notification add constraint notification_kind_known check (kind in (
    'APPROVAL_TASK', 'APPROVAL_REMINDER', 'APPROVAL_DECIDED', 'WORKFLOW_NOTICE', 'TRIAL_NOTICE', 'CHANGE_REQUEST',
    'DOCUMENT_PUBLISHED', 'DOCUMENT_AT_PLANT', 'ACTION_PLAN_NEEDED', 'WEBHOOK_STOPPED'));

comment on table webhook_subscription is
    'A webhook subscription: an address of the organization''s own software to be told when records change, the member whose rights it uses, and the breaker and bucket of the address. Never a secret (ADR-0274).';
comment on table webhook_delivery is
    'One event to tell one subscription about, as queued work. Identifiers, a stable reason and times only; never a payload body, header or answer (ADR-0278).';
comment on table webhook_attempt is
    'One attempt to send a webhook delivery: when, how long, how it ended, a stable reason and a class of status (ADR-0278).';
comment on table rate_limit_override is
    'Limits on the public API that the platform set for one organization instead of the defaults; the reason is in the audit trail only (ADR-0283).';
