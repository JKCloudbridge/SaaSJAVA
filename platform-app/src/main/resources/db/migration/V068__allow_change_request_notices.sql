-- A new kind of in-app notice and a new category of notices (Functional track FA5, ADR-0191). Expand only: the two lists
-- the database checks are widened, nothing is removed.
--
--   kind CHANGE_REQUEST       Something happened to a change request on a record: one was made, a comment was added, it
--                             was accepted, declined or turned into a new version. The notice stores facts only (which
--                             event, and where the record's page lives); the words are built when it is read.
--   category REQUESTS         What a person switches on or off for these notices (a switch per channel, like the other
--                             categories). It is not locked on: a person may choose not to be told.
--
-- notification_template is not widened: the text of this kind is the platform's own and cannot be written by an
-- administrator.

alter table notification drop constraint notification_kind_known;
alter table notification add constraint notification_kind_known check (kind in (
    'APPROVAL_TASK', 'APPROVAL_REMINDER', 'APPROVAL_DECIDED', 'WORKFLOW_NOTICE', 'TRIAL_NOTICE', 'CHANGE_REQUEST'));

alter table notification_preference drop constraint notification_preference_category_known;
alter table notification_preference add constraint notification_preference_category_known check (
    category in ('APPROVALS', 'WORKFLOWS', 'ACCOUNT', 'REQUESTS'));
