-- The e-mail queue of the notification module (Sprint 4, ADR-0024).
--
-- Platform-level on purpose (ADR-0024): a sign-up or a reset request comes from the platform host, where there is no
-- tenant, so the queue cannot be tenant-scoped, and the transactional outbox (ADR-0016) belongs to exactly one tenant
-- by design. The table has no tenant_id and no row level security.
--
-- A row says "this kind of mail for this address", never a finished message: it holds no token, no link and no body.
-- The relay decides what to send when it handles the row (and, for a link, creates the token at that moment), so a mail
-- that waits through an outage holds no secret and what is sent reflects the state of the account at send time.
-- Status: QUEUED -> SENT, or SUPPRESSED (decided at send time that nothing is to be sent), or DEAD (gave up).
-- The relay claims due rows with a lease, like the outbox: several instances never send the same row at once.

create table mail_queue (
    id              uuid        primary key default uuidv7(),
    template        text        not null,
    email           text        not null,
    user_id         uuid,
    variables       jsonb       not null default '{}'::jsonb,
    status          text        not null default 'QUEUED',
    attempts        integer     not null default 0,
    next_attempt_at timestamptz not null default now(),
    locked_until    timestamptz,
    last_error_type text,
    outcome         text,
    sent_at         timestamptz,
    version         bigint      not null default 0,
    created_at      timestamptz not null default now(),
    created_by      uuid        not null,
    updated_at      timestamptz not null default now(),
    updated_by      uuid        not null,
    deleted_at      timestamptz,
    deleted_by      uuid,
    constraint mail_queue_template_format check (template ~ '^[A-Z][A-Z_]*$'),
    constraint mail_queue_email_length check (char_length(email) between 3 and 254),
    constraint mail_queue_status_known check (status in ('QUEUED', 'SENT', 'SUPPRESSED', 'DEAD')),
    constraint mail_queue_attempts_positive check (attempts >= 0),
    -- The last failure is recorded as a type name only: library messages can quote the address and the server's reply.
    constraint mail_queue_error_type_length check (last_error_type is null or char_length(last_error_type) <= 200),
    constraint mail_queue_outcome_length check (outcome is null or char_length(outcome) <= 100)
);

create trigger mail_queue_row_guard before insert or update on mail_queue
    for each row execute function platform_row_guard();

-- The relay's question: what is due, oldest first. Small, because finished rows leave the index.
create index mail_queue_due on mail_queue (next_attempt_at, id) where status = 'QUEUED';
-- Limits per address and the once-a-day lock mail: recent rows of one address or one user.
create index mail_queue_email_time on mail_queue (email, created_at);
create index mail_queue_user_template_time on mail_queue (user_id, template, created_at) where user_id is not null;
-- Retention: finished rows are removed after a while.
create index mail_queue_finished on mail_queue (updated_at) where status <> 'QUEUED';

comment on table mail_queue is
    'Queue of e-mails to send (notification module). No token, link or body is ever stored. Platform-level (ADR-0024).';
