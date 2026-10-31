-- Mapping, external links, the record of every send and receive, and the replay store (Sprint 28, ADR-0216 to
-- ADR-0224). Expand only: nothing existing changes except one added policy that lets the inbound receiver find a
-- connection by its public identity.
--
-- A mapping says how the fields of one platform object correspond to the fields of one entity of an external system,
-- for one connection. It is NOT metadata (no change sets, no releases): it belongs to an environment, like the
-- connection it hangs on (ADR-0208, ADR-0217). Its versions are immutable once published, so an in-flight send or
-- receive is judged under the version it started with.
--
-- What no table here holds: a record value, an external payload, a header, a signature, a secret. integration_link
-- holds the identifier a record has in the other system, because that is its whole purpose; integration_delivery and
-- integration_inbound_seen hold identifiers, stable reason keys and times only.

-- ---------------------------------------------------------------------------------------------------- the mapping
create table integration_mapping (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    connection_id   uuid        not null references integration_connection (id),
    object_api_name text        not null,
    entity          text        not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint integration_mapping_object_shape check (length(object_api_name) between 1 and 100),
    constraint integration_mapping_entity_shape check (entity ~ '^[a-z][a-z0-9-]{0,39}$')
);

create unique index integration_mapping_tenant_object
    on integration_mapping (tenant_id, connection_id, object_api_name) where deleted_at is null;
create unique index integration_mapping_tenant_entity
    on integration_mapping (tenant_id, connection_id, entity) where deleted_at is null;

create function platform_integration_mapping_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' and (new.connection_id is distinct from old.connection_id
            or new.object_api_name is distinct from old.object_api_name
            or new.entity is distinct from old.entity) then
        raise exception 'integration guard: connection, object and entity of % are immutable', tg_table_name
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

create trigger integration_mapping_row_guard before insert or update on integration_mapping
    for each row execute function platform_row_guard();
create trigger integration_mapping_tenant_guard before update on integration_mapping
    for each row execute function platform_tenant_guard();
create trigger integration_mapping_shape_guard before insert or update on integration_mapping
    for each row execute function platform_integration_mapping_guard();

-- ------------------------------------------------------------------------------------------- the mapping versions
create table integration_mapping_version (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    mapping_id      uuid        not null references integration_mapping (id),
    number          integer     not null,
    status          text        not null default 'DRAFT',
    definition      jsonb       not null,
    definition_hash text        not null,
    published_at    timestamptz,
    published_by    uuid,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint integration_mapping_version_number check (number >= 1),
    constraint integration_mapping_version_status check (status in ('DRAFT', 'LIVE', 'SUPERSEDED')),
    constraint integration_mapping_version_definition check (jsonb_typeof(definition) = 'object'
        and length(definition::text) <= 200000),
    constraint integration_mapping_version_hash check (definition_hash ~ '^[0-9a-f]{64}$'),
    constraint integration_mapping_version_published check ((status = 'DRAFT') = (published_at is null)
        and (status = 'DRAFT') = (published_by is null))
);

create unique index integration_mapping_version_number_unique
    on integration_mapping_version (tenant_id, mapping_id, number) where deleted_at is null;
create unique index integration_mapping_version_one_draft
    on integration_mapping_version (tenant_id, mapping_id) where status = 'DRAFT' and deleted_at is null;
create unique index integration_mapping_version_one_live
    on integration_mapping_version (tenant_id, mapping_id) where status = 'LIVE' and deleted_at is null;

-- The life of a version, held at the database as well as in the application (ADR-0217): a draft is edited and then
-- published; what is published never changes; only the move live to superseded exists after that.
create function platform_integration_mapping_version_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if new.status <> 'DRAFT' then
            raise exception 'integration guard: a mapping version starts as a draft' using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if tg_op = 'DELETE' then
        if old.status <> 'DRAFT' then
            raise exception 'integration guard: a published mapping version is never removed'
                using errcode = 'check_violation';
        end if;
        return old;
    end if;

    if new.mapping_id is distinct from old.mapping_id or new.number is distinct from old.number then
        raise exception 'integration guard: mapping and number of % are immutable', tg_table_name
            using errcode = 'check_violation';
    end if;
    if new.deleted_at is not null then
        raise exception 'integration guard: a mapping version is removed by discarding its draft'
            using errcode = 'check_violation';
    end if;
    if old.status = 'SUPERSEDED' then
        raise exception 'integration guard: a superseded mapping version is final' using errcode = 'check_violation';
    end if;
    if old.status = 'LIVE' then
        if new.status not in ('LIVE', 'SUPERSEDED') or new.definition is distinct from old.definition
                or new.definition_hash is distinct from old.definition_hash
                or new.published_at is distinct from old.published_at
                or new.published_by is distinct from old.published_by then
            raise exception 'integration guard: a published mapping version cannot be changed'
                using errcode = 'check_violation';
        end if;
        return new;
    end if;
    -- old.status = 'DRAFT'
    if new.status not in ('DRAFT', 'LIVE') then
        raise exception 'integration guard: this move of % is not allowed', tg_table_name
            using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

create trigger integration_mapping_version_row_guard before insert or update on integration_mapping_version
    for each row execute function platform_row_guard();
create trigger integration_mapping_version_tenant_guard before update on integration_mapping_version
    for each row execute function platform_tenant_guard();
create trigger integration_mapping_version_lifecycle_guard before insert or update or delete
    on integration_mapping_version
    for each row execute function platform_integration_mapping_version_guard();

-- ------------------------------------------------------------------------------------------------- the link table
-- Which record is which entity of the other system, for one connection. Unique both ways, so an inbound change finds
-- its record and an outbound change finds its entity (ADR-0219).
create table integration_link (
    id                  uuid        primary key default uuidv7(),
    tenant_id           uuid        not null default platform_current_tenant() references tenant (id),
    connection_id       uuid        not null references integration_connection (id),
    object_api_name     text        not null,
    record_id           uuid        not null,
    external_id         text        not null,
    last_hash           text,
    last_direction      text        not null,
    last_synced_at      timestamptz not null default now(),
    version             bigint      not null default 0,
    created_at          timestamptz not null default now(),
    created_by          uuid        not null,
    updated_at          timestamptz not null default now(),
    updated_by          uuid        not null,
    deleted_at          timestamptz,
    deleted_by          uuid,
    constraint integration_link_external_bound check (length(external_id) between 1 and 200),
    constraint integration_link_hash_shape check (last_hash is null or last_hash ~ '^[0-9a-f]{64}$'),
    constraint integration_link_direction check (last_direction in ('OUT', 'IN'))
);

create unique index integration_link_tenant_record
    on integration_link (tenant_id, connection_id, object_api_name, record_id) where deleted_at is null;
create unique index integration_link_tenant_external
    on integration_link (tenant_id, connection_id, object_api_name, external_id) where deleted_at is null;

create function platform_integration_link_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' and (new.connection_id is distinct from old.connection_id
            or new.object_api_name is distinct from old.object_api_name
            or new.record_id is distinct from old.record_id
            or new.external_id is distinct from old.external_id) then
        raise exception 'integration guard: what a link joins never changes' using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

create trigger integration_link_row_guard before insert or update on integration_link
    for each row execute function platform_row_guard();
create trigger integration_link_tenant_guard before update on integration_link
    for each row execute function platform_tenant_guard();
create trigger integration_link_shape_guard before insert or update on integration_link
    for each row execute function platform_integration_link_guard();

-- -------------------------------------------------------------------------------------------- the delivery record
-- One row per send (direction OUT, once per event and connection) or per accepted receive (direction IN). Facts only.
create table integration_delivery (
    id                  uuid        primary key default uuidv7(),
    tenant_id           uuid        not null default platform_current_tenant() references tenant (id),
    connection_id       uuid        not null references integration_connection (id),
    mapping_version_id  uuid        not null references integration_mapping_version (id),
    direction           text        not null,
    kind                text        not null,
    event_id            uuid        not null,
    object_api_name     text        not null,
    record_id           uuid,
    state               text        not null default 'PENDING',
    reason              text,
    http_status         integer,
    finished_at         timestamptz,
    version             bigint      not null default 0,
    created_at          timestamptz not null default now(),
    created_by          uuid        not null,
    updated_at          timestamptz not null default now(),
    updated_by          uuid        not null,
    deleted_at          timestamptz,
    deleted_by          uuid,
    constraint integration_delivery_direction check (direction in ('OUT', 'IN')),
    constraint integration_delivery_kind check (kind in ('CREATE', 'UPDATE', 'DELETE', 'UPSERT')),
    constraint integration_delivery_state check (state in ('PENDING', 'DONE', 'FAILED', 'SKIPPED')),
    constraint integration_delivery_reason_shape check (reason is null or reason ~ '^[a-z_]{1,40}$'),
    constraint integration_delivery_status_range check (http_status is null or http_status between 100 and 599),
    constraint integration_delivery_finished check ((state = 'PENDING') = (finished_at is null))
);

create unique index integration_delivery_tenant_event
    on integration_delivery (tenant_id, connection_id, direction, event_id) where deleted_at is null;
create index integration_delivery_tenant_recent
    on integration_delivery (tenant_id, connection_id, created_at desc) where deleted_at is null;

create function platform_integration_delivery_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if new.state not in ('PENDING', 'SKIPPED') then
            raise exception 'integration guard: a delivery starts pending or skipped' using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if new.connection_id is distinct from old.connection_id
            or new.mapping_version_id is distinct from old.mapping_version_id
            or new.direction is distinct from old.direction or new.kind is distinct from old.kind
            or new.event_id is distinct from old.event_id
            or new.object_api_name is distinct from old.object_api_name then
        raise exception 'integration guard: what a delivery is about never changes' using errcode = 'check_violation';
    end if;
    if old.state <> 'PENDING' then
        raise exception 'integration guard: a finished delivery is final' using errcode = 'check_violation';
    end if;
    if new.state = 'PENDING' then
        raise exception 'integration guard: a pending delivery is finished by this change' using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

create trigger integration_delivery_row_guard before insert or update on integration_delivery
    for each row execute function platform_row_guard();
create trigger integration_delivery_tenant_guard before update on integration_delivery
    for each row execute function platform_tenant_guard();
create trigger integration_delivery_lifecycle_guard before insert or update on integration_delivery
    for each row execute function platform_integration_delivery_guard();

-- ------------------------------------------------------------------------------------------------ the replay store
-- The hash of the nonce of every signed inbound call that was accepted, for the replay window. Nothing else: not the
-- nonce, not the signature, not the body. Rows older than the window are removed by the receiver as it goes.
create table integration_inbound_seen (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    connection_id   uuid        not null references integration_connection (id),
    nonce_hash      text        not null,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint integration_inbound_seen_hash check (nonce_hash ~ '^[0-9a-f]{64}$')
);

create unique index integration_inbound_seen_tenant_nonce
    on integration_inbound_seen (tenant_id, connection_id, nonce_hash) where deleted_at is null;
create index integration_inbound_seen_tenant_age
    on integration_inbound_seen (tenant_id, created_at) where deleted_at is null;

create function platform_integration_inbound_seen_guard() returns trigger
    language plpgsql
as $guard$
begin
    raise exception 'integration guard: a replay record is never changed' using errcode = 'check_violation';
end;
$guard$;

create trigger integration_inbound_seen_row_guard before insert or update on integration_inbound_seen
    for each row execute function platform_row_guard();
create trigger integration_inbound_seen_tenant_guard before update on integration_inbound_seen
    for each row execute function platform_tenant_guard();
create trigger integration_inbound_seen_immutable before update on integration_inbound_seen
    for each row execute function platform_integration_inbound_seen_guard();

-- --------------------------------------------------------------------------------------------- row level security
alter table integration_mapping enable row level security;
alter table integration_mapping force row level security;
create policy integration_mapping_insert on integration_mapping for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy integration_mapping_select on integration_mapping for select
    using (tenant_id = (select platform_current_tenant()));
create policy integration_mapping_update on integration_mapping for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy integration_mapping_delete on integration_mapping for delete
    using (tenant_id = (select platform_current_tenant()));

alter table integration_mapping_version enable row level security;
alter table integration_mapping_version force row level security;
create policy integration_mapping_version_insert on integration_mapping_version for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy integration_mapping_version_select on integration_mapping_version for select
    using (tenant_id = (select platform_current_tenant()));
create policy integration_mapping_version_update on integration_mapping_version for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy integration_mapping_version_delete on integration_mapping_version for delete
    using (tenant_id = (select platform_current_tenant()));

alter table integration_link enable row level security;
alter table integration_link force row level security;
create policy integration_link_insert on integration_link for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy integration_link_select on integration_link for select
    using (tenant_id = (select platform_current_tenant()));
create policy integration_link_update on integration_link for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy integration_link_delete on integration_link for delete
    using (tenant_id = (select platform_current_tenant()));

alter table integration_delivery enable row level security;
alter table integration_delivery force row level security;
create policy integration_delivery_insert on integration_delivery for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy integration_delivery_select on integration_delivery for select
    using (tenant_id = (select platform_current_tenant()));
create policy integration_delivery_update on integration_delivery for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy integration_delivery_delete on integration_delivery for delete
    using (tenant_id = (select platform_current_tenant()));

alter table integration_inbound_seen enable row level security;
alter table integration_inbound_seen force row level security;
create policy integration_inbound_seen_insert on integration_inbound_seen for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy integration_inbound_seen_select on integration_inbound_seen for select
    using (tenant_id = (select platform_current_tenant()));
create policy integration_inbound_seen_update on integration_inbound_seen for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy integration_inbound_seen_delete on integration_inbound_seen for delete
    using (tenant_id = (select platform_current_tenant()));

-- The receiver finds a connection, and through it the organization, from the public identity in the address alone
-- (ADR-0221). Reading only, in the one named scope; the code always asks by that identity.
create policy integration_connection_inbound_lookup on integration_connection for select
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('inbound_lookup'));

comment on table integration_mapping is
    'How one platform object corresponds to one entity of an external system, for one connection. Not metadata (ADR-0217).';
comment on table integration_mapping_version is
    'A draft or published version of a mapping; a published definition never changes (ADR-0217).';
comment on table integration_link is
    'Which record is which entity of the other system, unique both ways (ADR-0219). Holds the external identifier only.';
comment on table integration_delivery is
    'One send or accepted receive: identifiers, a stable reason, a status and times. Never a value or a payload (ADR-0220).';
comment on table integration_inbound_seen is
    'Hashes of accepted inbound nonces for the replay window (ADR-0221). Nothing else.';
