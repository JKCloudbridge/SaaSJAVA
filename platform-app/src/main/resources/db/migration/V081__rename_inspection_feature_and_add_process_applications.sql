-- The plan features behind the Quality Management System and the process applications (Functional track FA9, ADR-0203).
--
-- 1. The shipped application "Audit maintenance" is now called "Quality Management System". The feature key
--    `inspection` is permanent (plans, overrides and the audit trail point at it); only the name the console shows
--    changes, so the console and the application say the same words.
-- 2. The process applications (marketing, production, quality assurance and the rest of the business's functions) are
--    announced before they are built. They have a plan feature of their own so an organization can have them with or
--    without the Quality Management System. They hold no data and no licence: the feature is in no plan by default,
--    and a platform administrator adds it to a plan or gives one organization an override in the console.
--
-- Expand only: one changed label and one new row. Nothing reads the old name.

update feature set name = 'Quality Management System', version = version + 1,
    updated_by = '00000000-0000-0000-0000-000000000000'
where key = 'inspection' and name = 'Audit maintenance';

insert into feature (key, name, created_by, updated_by)
values ('process-applications', 'Process applications', '00000000-0000-0000-0000-000000000000',
        '00000000-0000-0000-0000-000000000000');
