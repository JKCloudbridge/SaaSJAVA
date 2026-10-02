-- Marks the invitation a platform administrator makes for the first administrator of an organization (Sprint 6, ADR-0037).
--
-- Backward compatible (expand): a column with a default. An invitation made by an organization's own administrators is
-- false; the invitation that provisioning an organization for a client (or repairing one that lost every administrator)
-- creates is true. The mail relay uses it to choose the words ("set up and administer"), and the console uses it to show
-- the state of that one invitation, without ever showing the address.

alter table invitation add column invited_by_platform boolean not null default false;

comment on column invitation.invited_by_platform is
    'True for the invitation of a first administrator made by a platform administrator (ADR-0037).';
