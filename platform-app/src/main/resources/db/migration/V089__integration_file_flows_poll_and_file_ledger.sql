-- File transfer flows (Sprint 29A, ADR-0237 to ADR-0243). Expand only: a connection may now sign in with a user name and
-- a private key, two tables are added, and one policy learns the system scope of the outbound worker. Nothing is
-- removed and no existing row changes meaning.
--
-- integration_poll   one row per connection that picks files up: when to look next, the lease that keeps two instances
--                    from looking at once, and what the last look found. Counts, times and stable reasons only.
-- integration_file   the ledger of the files picked up: one row per file taken from an inbox, written BEFORE the file is
--                    moved so that a restart at any point finds it, and updated with the progress of the rows applied
--                    in the same transaction as the rows themselves. It holds a hash of the file's name, size and time,
--                    a hash of its content, counts and a stable reason. It never holds a file name, a path, a row or a
--                    value: the name of a file is chosen by the other side, so it is data of the organization.

-- ------------------------------------------------------------------------------------------------ the sign-in kind
alter table integration_connection drop constraint integration_connection_auth_kind;
alter table integration_connection
    add constraint integration_connection_auth_kind check (auth_kind in ('NONE', 'BEARER_TOKEN', 'API_KEY_HEADER',
        'BASIC', 'SSH_KEY'));

-- ----------------------------------------------------------------------------------------------------- the poll
create table integration_poll (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    connection_id   uuid        not null references integration_connection (id),
    next_poll_at    timestamptz not null default now(),
    locked_until    timestamptz,
    last_poll_at    timestamptz,
    last_outcome    text,
    last_reason     text,
    files_waiting   integer     not null default 0,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint integration_poll_outcome check (last_outcome is null
        or last_outcome in ('LOOKED', 'HELD', 'FAILED')),
    constraint integration_poll_reason_shape check (last_reason is null or last_reason ~ '^[a-z_]{1,40}$'),
    constraint integration_poll_waiting check (files_waiting >= 0)
);

create unique index integration_poll_tenant_connection
    on integration_poll (tenant_id, connection_id) where deleted_at is null;
-- The claim: the polls that are due, oldest first, in every organization.
create index integration_poll_due on integration_poll (next_poll_at, id) where deleted_at is null;

create function platform_integration_poll_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'UPDATE' and new.connection_id is distinct from old.connection_id then
        raise exception 'integration guard: the connection of a poll never changes' using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

create trigger integration_poll_row_guard before insert or update on integration_poll
    for each row execute function platform_row_guard();
create trigger integration_poll_tenant_guard before update on integration_poll
    for each row execute function platform_tenant_guard();
create trigger integration_poll_shape_guard before insert or update on integration_poll
    for each row execute function platform_integration_poll_guard();

-- ----------------------------------------------------------------------------------------------- the file ledger
create table integration_file (
    id              uuid        primary key default uuidv7(),
    tenant_id       uuid        not null default platform_current_tenant() references tenant (id),
    connection_id   uuid        not null references integration_connection (id),
    file_key        text        not null,
    content_hash    text,
    size_bytes      bigint      not null,
    state           text        not null default 'CLAIMING',
    rows_total      integer,
    rows_done       integer     not null default 0,
    rows_applied    integer     not null default 0,
    rows_skipped    integer     not null default 0,
    rows_refused    integer     not null default 0,
    reason          text,
    finished_at     timestamptz,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint integration_file_state check (state in ('CLAIMING', 'CLAIMED', 'APPLIED', 'REJECTED', 'DONE',
        'SET_ASIDE', 'ABANDONED')),
    constraint integration_file_key_shape check (file_key ~ '^[0-9a-f]{64}$'),
    constraint integration_file_hash_shape check (content_hash is null or content_hash ~ '^[0-9a-f]{64}$'),
    constraint integration_file_reason_shape check (reason is null or reason ~ '^[a-z_]{1,40}$'),
    constraint integration_file_counts check (size_bytes >= 0 and rows_done >= 0 and rows_applied >= 0
        and rows_skipped >= 0 and rows_refused >= 0 and (rows_total is null or rows_done <= rows_total)),
    constraint integration_file_finished check (
        (state in ('DONE', 'SET_ASIDE', 'ABANDONED')) = (finished_at is not null))
);

create index integration_file_tenant_connection_state
    on integration_file (tenant_id, connection_id, state, created_at) where deleted_at is null;
-- A file that was applied completely is processed once: the same name, size and time again is a duplicate. A file that
-- was set aside is deliberately not in this index, so a person who moves it back to the inbox gets it tried again.
create unique index integration_file_done_once
    on integration_file (tenant_id, connection_id, file_key) where state = 'DONE' and deleted_at is null;

-- The life of a ledger row, held at the database whatever the application does (ADR-0242).
create function platform_integration_file_guard() returns trigger
    language plpgsql
as $guard$
begin
    if tg_op = 'INSERT' then
        if new.state <> 'CLAIMING' or new.rows_done <> 0 or new.rows_applied <> 0 or new.rows_skipped <> 0
                or new.rows_refused <> 0 then
            raise exception 'integration guard: a file starts as a claim with nothing applied'
                using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if new.connection_id is distinct from old.connection_id or new.file_key is distinct from old.file_key
            or new.size_bytes is distinct from old.size_bytes then
        raise exception 'integration guard: what a file is never changes' using errcode = 'check_violation';
    end if;
    if old.state in ('DONE', 'SET_ASIDE', 'ABANDONED') then
        raise exception 'integration guard: a finished file is final' using errcode = 'check_violation';
    end if;
    if not (
        (old.state = 'CLAIMING' and new.state in ('CLAIMING', 'CLAIMED', 'ABANDONED'))
        or (old.state = 'CLAIMED' and new.state in ('CLAIMED', 'APPLIED', 'REJECTED'))
        or (old.state = 'APPLIED' and new.state in ('APPLIED', 'DONE', 'SET_ASIDE'))
        or (old.state = 'REJECTED' and new.state in ('REJECTED', 'SET_ASIDE'))) then
        raise exception 'integration guard: this move of a file is not allowed' using errcode = 'check_violation';
    end if;
    if new.rows_done < old.rows_done or new.rows_applied < old.rows_applied or new.rows_skipped < old.rows_skipped
            or new.rows_refused < old.rows_refused then
        raise exception 'integration guard: progress is never taken back' using errcode = 'check_violation';
    end if;
    if old.rows_total is not null and new.rows_total is distinct from old.rows_total then
        raise exception 'integration guard: the number of rows of a file never changes'
            using errcode = 'check_violation';
    end if;
    if new.state = 'APPLIED' and new.rows_done is distinct from new.rows_total then
        raise exception 'integration guard: a file is applied only when every row is' using errcode = 'check_violation';
    end if;
    if old.content_hash is not null and new.content_hash is distinct from old.content_hash then
        raise exception 'integration guard: the content of a file never changes' using errcode = 'check_violation';
    end if;
    return new;
end;
$guard$;

create trigger integration_file_row_guard before insert or update on integration_file
    for each row execute function platform_row_guard();
create trigger integration_file_tenant_guard before update on integration_file
    for each row execute function platform_tenant_guard();
create trigger integration_file_shape_guard before insert or update on integration_file
    for each row execute function platform_integration_file_guard();

-- --------------------------------------------------------------------------------------------- row level security
alter table integration_poll enable row level security;
alter table integration_poll force row level security;
create policy integration_poll_insert on integration_poll for insert
    with check (tenant_id = (select platform_current_tenant()));
-- The outbound worker finds and claims due polls of every organization in the one scope it already uses for deliveries
-- (ADR-0231, ADR-0242). It may read and claim rows of integration_poll and nothing else; the work of a claimed poll
-- runs in the organization's own tenant context.
create policy integration_poll_select on integration_poll for select
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('integration_dispatch'));
create policy integration_poll_update on integration_poll for update
    using (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('integration_dispatch'))
    with check (tenant_id = (select platform_current_tenant()) or platform_in_system_scope('integration_dispatch'));
create policy integration_poll_delete on integration_poll for delete
    using (tenant_id = (select platform_current_tenant()));

alter table integration_file enable row level security;
alter table integration_file force row level security;
create policy integration_file_insert on integration_file for insert
    with check (tenant_id = (select platform_current_tenant()));
create policy integration_file_select on integration_file for select
    using (tenant_id = (select platform_current_tenant()));
create policy integration_file_update on integration_file for update
    using (tenant_id = (select platform_current_tenant()))
    with check (tenant_id = (select platform_current_tenant()));
create policy integration_file_delete on integration_file for delete
    using (tenant_id = (select platform_current_tenant()));

comment on table integration_poll is
    'When a file transfer connection next looks at its inbox, the lease, and what the last look found. Counts, times and stable reasons only (ADR-0242).';
comment on table integration_file is
    'One file taken from an inbox: a hash of its name, size and time, a hash of its content, progress and a stable reason. Never a name, a path, a row or a value (ADR-0242).';
