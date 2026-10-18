-- Sprint 23: retries with backoff, dead letters, replay and the chain that protects against loops
-- (ADR-0140 to ADR-0143). Expand only: columns with defaults and widened checks; nothing is removed or renamed, so the
-- previous release keeps working against this schema.
--
-- workflow_step       gains the attempts of the step, when the next one is due, and the type name and stable reason of
--                     the last failure; a step waiting for its next attempt is RETRYING.
-- workflow_execution  gains the chain (root, depth, workflows already in it), the link to the execution it replays and the
--                     step it resumes at; DEAD_LETTERED becomes a terminal status.
--
-- Both tables are tenant-scoped with forced row level security already (V053); no policy changes: the new columns
-- carry no value of a record, only identifiers, numbers, a stable reason and a type name.

-- ---------------------------------------------------------------------------------------------------------------
-- workflow_step
-- ---------------------------------------------------------------------------------------------------------------

alter table workflow_step
    add column attempts          integer     not null default 0,
    add column next_attempt_at   timestamptz,
    add column last_failure_type text,
    add column last_reason       text;

alter table workflow_step drop constraint workflow_step_status_known;
alter table workflow_step add constraint workflow_step_status_known
    check (status in ('RUNNING', 'RETRYING', 'COMPLETED', 'FAILED'));

-- A step that is running or waiting for its next attempt has no end; the others have one.
alter table workflow_step drop constraint workflow_step_end_known;
alter table workflow_step add constraint workflow_step_end_known
    check ((status in ('RUNNING', 'RETRYING')) = (finished_at is null));

alter table workflow_step add constraint workflow_step_attempts_bounded check (attempts between 0 and 100);
alter table workflow_step add constraint workflow_step_last_failure_length
    check (last_failure_type is null or char_length(last_failure_type) <= 200);
-- A reason is a stable key, never a sentence and never a value.
alter table workflow_step add constraint workflow_step_last_reason_shape
    check (last_reason is null or last_reason ~ '^[a-z][a-z_]{0,59}$');

comment on column workflow_step.attempts is
    'Attempts of this step that ended (failed or succeeded). A worker that died mid-attempt leaves no mark: the claim counter of the execution counts it (ADR-0141).';
comment on column workflow_step.next_attempt_at is
    'When the next attempt is due while the step is RETRYING (ADR-0142). For display: the execution''s run_at is what the dispatcher claims by.';

-- ---------------------------------------------------------------------------------------------------------------
-- workflow_execution
-- ---------------------------------------------------------------------------------------------------------------

alter table workflow_execution
    add column chain_root        uuid,
    add column chain_depth       integer     not null default 0,
    add column chain_workflows   uuid[]      not null default '{}',
    add column replay_of         uuid,
    add column replay_generation integer     not null default 0,
    add column resume_step       integer     not null default 1;

alter table workflow_execution drop constraint workflow_execution_status_known;
alter table workflow_execution add constraint workflow_execution_status_known
    check (status in ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED', 'DEAD_LETTERED'));

alter table workflow_execution drop constraint workflow_execution_end_known;
alter table workflow_execution add constraint workflow_execution_end_known
    check ((status in ('COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED', 'DEAD_LETTERED')) = (finished_at is not null));

-- A cause is an event, a firing of a schedule, or a person's replay of an earlier execution.
alter table workflow_execution drop constraint workflow_execution_one_cause;
alter table workflow_execution add constraint workflow_execution_one_cause
    check (num_nonnulls(event_id, firing_at, replay_of) = 1);

alter table workflow_execution add constraint workflow_execution_chain_bounded
    check (chain_depth between 0 and 1000 and cardinality(chain_workflows) <= 1000);
alter table workflow_execution add constraint workflow_execution_replay_known
    check ((replay_of is null) = (replay_generation = 0) and replay_generation >= 0);
alter table workflow_execution add constraint workflow_execution_resume_positive check (resume_step >= 1);

-- A replay of an execution is made once: two clicks at the same moment make one (ADR-0141).
create unique index workflow_execution_replay on workflow_execution (tenant_id, replay_of, replay_generation)
    where replay_of is not null and deleted_at is null;
create index workflow_execution_replay_of on workflow_execution (replay_of) where replay_of is not null;
-- The dead letters of one organization, newest first (the list and the gauge).
create index workflow_execution_dead on workflow_execution (tenant_id, finished_at desc, id desc)
    where status = 'DEAD_LETTERED' and deleted_at is null;
-- How many executions an object made in the last minute (the rate guard of the handler, ADR-0140).
create index workflow_execution_tenant_object_created on workflow_execution (tenant_id, object_api_name, created_at desc);

comment on column workflow_execution.chain_root is
    'The first cause of the chain this execution belongs to: the event of a person''s write, or the execution a schedule firing started. Null on rows made before Sprint 23 (ADR-0140).';
comment on column workflow_execution.chain_depth is
    'How many workflow executions lead to this one; a person''s write gives 0 (ADR-0140).';
comment on column workflow_execution.chain_workflows is
    'The workflows already in the chain, not counting this one; a workflow already in it is not started again (ADR-0140).';
comment on column workflow_execution.replay_of is
    'The execution this one replays (ADR-0141). Replay resumes at resume_step against the record as it is now. No foreign key: the clean-up removes an old dead letter that a newer replay still names.';
