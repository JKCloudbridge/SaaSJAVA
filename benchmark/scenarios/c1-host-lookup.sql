-- Carried note (Sprint 2): the lookup of an organization by its short name, made on every request before any tenant
-- context exists. The table has as many rows as the run has organizations.
\set k random(1, :tenants)
select id, slug, display_name, status, status_changed_at, version from tenant
 where slug = case when :k = 1 then 'bench-large' else 'bench-' || lpad((:k)::text, 5, '0') end and deleted_at is null;
