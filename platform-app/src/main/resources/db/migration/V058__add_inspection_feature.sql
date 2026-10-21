-- The plan feature of the first platform-shipped cluster (Functional track FA1, ADR-0179).
--
-- A feature key is a platform-level fact (ADR-0031): a plan may include it and a platform administrator may switch it on
-- or off for one organization. It is asked with Entitlements.enabled("inspection") in the one place that gates the
-- cluster. There is no licence type and no plan quantity: the cluster is sold to the whole organization, and the access
-- policies it ships use no seat (ADR-0046). The feature is in no plan by default; a platform administrator adds it to a
-- plan or gives one organization an override in the console.
--
-- The name is the business's own word for the function, shown in the console. Expand only: one new row.

insert into feature (key, name, created_by, updated_by)
values ('inspection', 'Audit maintenance', '00000000-0000-0000-0000-000000000000',
        '00000000-0000-0000-0000-000000000000');
