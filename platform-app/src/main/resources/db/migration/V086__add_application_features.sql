-- The plan features behind the Document Management, CAPA, Audit Management, Supplier Management and Management Core
-- applications (Functional track FA13A, ADR-0225).
--
-- Each application can be sold on its own, so each has a feature of its own. Like the features of V081 they are in no plan by
-- default: a platform administrator adds one to a plan, or gives one organization an override, in the console. The key is
-- permanent (plans, overrides and the audit trail point at it); the name is what the console shows.
--
-- Expand only: five new rows. Nothing reads them until a solution file names the key.

insert into feature (key, name, created_by, updated_by)
values ('document-management', 'Document Management', '00000000-0000-0000-0000-000000000000',
        '00000000-0000-0000-0000-000000000000'),
       ('capa', 'CAPA', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
       ('audit-management', 'Audit Management', '00000000-0000-0000-0000-000000000000',
        '00000000-0000-0000-0000-000000000000'),
       ('supplier-management', 'Supplier Management', '00000000-0000-0000-0000-000000000000',
        '00000000-0000-0000-0000-000000000000'),
       ('management-core', 'Management Core', '00000000-0000-0000-0000-000000000000',
        '00000000-0000-0000-0000-000000000000');
