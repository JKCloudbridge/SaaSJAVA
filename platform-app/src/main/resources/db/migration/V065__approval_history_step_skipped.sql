-- A step of an approval that nobody can be asked to decide may be skipped when its process says so (FA4A, ADR-0187).
-- Expand only: one more line in the history of a request. Nothing is removed or renamed, so the previous release keeps
-- working.
--
-- Nothing else needs the database. The step table already allows the status SKIPPED (V055: the steps not reached when a
-- request ends), and its check "approval_step_times_known" already asks a skipped step for a finish time and for
-- nothing else. Whether a step may be skipped is a property of the process version, which is stored as JSON in
-- approval_process_version.steps; a version stored before the flag existed has no such key and means "never skipped".

alter table approval_history drop constraint approval_history_kind_known;
alter table approval_history add constraint approval_history_kind_known check (kind in (
    'SUBMITTED', 'STEP_ACTIVATED', 'TASK_APPROVED', 'STEP_APPROVED', 'APPROVED', 'REJECTED', 'RECALLED',
    'CANCELLED',
    'ASSIGNEE_DROPPED', 'REASSIGNED', 'ASSIGNED', 'ATTENTION_RAISED', 'ATTENTION_CLEARED',
    'OUTCOME_APPLIED', 'OUTCOME_FAILED', 'OUTCOME_REAPPLIED', 'LOCK_OVERRIDDEN',
    'REMINDER_SENT', 'ESCALATED', 'OVERDUE_MARKED',
    'STEP_SKIPPED')) not valid;
alter table approval_history validate constraint approval_history_kind_known;
